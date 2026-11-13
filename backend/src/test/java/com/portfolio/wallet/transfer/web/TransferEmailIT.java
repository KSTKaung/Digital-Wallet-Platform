package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.support.OutboxRows.Row;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Transfer emails (documentations/06 MVP-ADR-24 amendment "Emails (Q9)", MVP-ADR-25; doc 08 §4C.2
 * Q9, §4C.4 criterion 19): written in the business transaction and sent after the commit, with the
 * amount, the currency and the server reference only; {@code TRANSFER_SENT} and {@code
 * TRANSFER_RECEIVED} on completion, {@code TRANSFER_HELD} to the sender only, {@code
 * TRANSFER_CANCELLED} on a cancel and {@code TRANSFER_EXPIRED} on a late cancel. Nothing for a
 * {@code BLOCK}, a stored refusal, a replay (in the key's window or after it), a {@code 409}, a
 * rollback, or to the receiver before completion. Each test reads only its own accounts' rows.
 */
class TransferEmailIT extends TransferReviewFlowSupport {

  private static final String NOTE = "note-5b2e";

  private MockHttpServletResponse send(Account sender, Account recipient, String amount, UUID key)
      throws Exception {
    return postTransfer(
        transferBody(numberOf(recipient), amount, NOTE), sender.token(), key, randomIp());
  }

  private static Answer<Object> callRealThen(RuntimeException failure) {
    return invocation -> {
      invocation.callRealMethod();
      throw failure;
    };
  }

  /** Nothing a customer must not read in an email: description, numbers, names, risk details. */
  private void assertNothingElse(EmailMessage message, Account... parties) {
    String text = message.subject() + " " + message.body();
    assertThat(text)
        .doesNotContain(NOTE, "Somchai", "Jaidee", "balance", "score", "rule")
        .doesNotContainIgnoringCase("risk");
    for (Account party : parties) {
      assertThat(text).doesNotContain(numberOf(party), walletId(party).toString());
    }
  }

  @Test
  void aCompletedTransferEmailsTheSenderAndTheReceiver() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();

    MockHttpServletResponse response = send(sender, recipient, ALLOW, UUID.randomUUID());

    assertThat(response.getStatus()).isEqualTo(201);
    String reference = body(response).get("reference").asString();
    List<Row> sent = transferEmails(sender);
    List<Row> received = transferEmails(recipient);
    assertThat(sent).extracting(Row::kind).containsExactly("TRANSFER_SENT");
    assertThat(received).extracting(Row::kind).containsExactly("TRANSFER_RECEIVED");
    assertThat(sent.getFirst().values()).isEqualTo(emailValues("999.99", reference));
    assertThat(received.getFirst().values()).isEqualTo(emailValues("999.99", reference));

    EmailMessage toSender = delivered(sender, sent.getFirst());
    assertThat(toSender.subject()).isEqualTo("Your transfer was completed");
    assertThat(toSender.body()).contains("999.99 THB", reference, "(Asia/Bangkok time)");
    // The sender's balance after the transfer is 1,000.01.
    assertThat(toSender.body()).doesNotContain("1000.01", "1,000.01");
    assertNothingElse(toSender, sender, recipient);
    EmailMessage toRecipient = delivered(recipient, received.getFirst());
    assertThat(toRecipient.subject()).isEqualTo("You received a transfer");
    assertThat(toRecipient.body()).contains("999.99 THB", reference);
    assertNothingElse(toRecipient, sender, recipient);
  }

  @Test
  void aHeldTransferEmailsTheSenderOnlyAndACancelEmailsTheSenderAgain() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    String reference = (String) transferRow(id).get("reference");

    List<Row> held = transferEmails(sender);
    assertThat(held).extracting(Row::kind).containsExactly("TRANSFER_HELD");
    assertThat(held.getFirst().values()).isEqualTo(emailValues("1300.00", reference));
    assertThat(transferEmails(recipient)).as("nothing to the receiver before completion").isEmpty();
    EmailMessage heldMessage = delivered(sender, held.getFirst());
    assertThat(heldMessage.subject()).isEqualTo("Your transfer is waiting for review");
    assertThat(heldMessage.body()).contains("1,300.00 THB", reference, "cancel");
    // The sender's available balance while held is 700.00.
    assertThat(heldMessage.body()).doesNotContain("700.00");
    assertNothingElse(heldMessage, sender, recipient);

    assertThat(cancel(id, sender).getStatus()).isEqualTo(200);

    List<Row> after = transferEmails(sender);
    assertThat(after).extracting(Row::kind).containsExactly("TRANSFER_HELD", "TRANSFER_CANCELLED");
    assertThat(after.get(1).values()).isEqualTo(emailValues("1300.00", reference));
    EmailMessage cancelled = delivered(sender, after.get(1));
    assertThat(cancelled.subject()).isEqualTo("Your transfer was cancelled");
    assertThat(cancelled.body()).contains("1,300.00 THB", reference);
    assertNothingElse(cancelled, sender, recipient);
    assertThat(transferEmails(recipient)).isEmpty();

    // A repeated cancel is 409 and writes nothing.
    assertThat(cancel(id, sender).getStatus()).isEqualTo(409);
    assertThat(transferEmails(sender)).hasSize(2);
  }

  @Test
  void aCancelAfterTheDeadlineEmailsTransferExpiredOnce() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    String reference = (String) transferRow(id).get("reference");
    overdue(id, 60);

    assertThat(cancel(id, sender).getStatus()).isEqualTo(409);
    assertThat(cancel(id, sender).getStatus()).isEqualTo(409);

    List<Row> rows = transferEmails(sender);
    assertThat(rows).extracting(Row::kind).containsExactly("TRANSFER_HELD", "TRANSFER_EXPIRED");
    assertThat(rows.get(1).values()).isEqualTo(emailValues("1300.00", reference));
    EmailMessage expired = delivered(sender, rows.get(1));
    assertThat(expired.subject()).isEqualTo("Your transfer has expired");
    assertNothingElse(expired, sender, recipient);
    assertThat(transferEmails(recipient)).isEmpty();
  }

  @Test
  void aBlockAndItsReplaySendNoEmail() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();

    assertThat(errorCode(send(sender, recipient, BLOCK, key))).isEqualTo("RISK_BLOCKED");
    assertThat(errorCode(send(sender, recipient, BLOCK, key))).isEqualTo("RISK_BLOCKED");

    assertThat(transfersOf(sender)).extracting(row -> row.get("status")).containsExactly("BLOCKED");
    assertThat(transferEmails(sender)).isEmpty();
    assertThat(transferEmails(recipient)).isEmpty();
  }

  @Test
  void aStoredRefusalAndItsReplaySendNoEmail() throws Exception {
    Account sender = funded("500.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();

    assertThat(errorCode(send(sender, recipient, ALLOW, key))).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(errorCode(send(sender, recipient, ALLOW, key))).isEqualTo("INSUFFICIENT_BALANCE");

    assertThat(transferEmails(sender)).isEmpty();
    assertThat(transferEmails(recipient)).isEmpty();
  }

  @Test
  void replaysAndKeyReuseSendNoEmailInTheWindowOrAfterTheRecordIsGone() throws Exception {
    Account sender = funded("5000.00");
    Account recipient = withWallet();
    UUID completed = UUID.randomUUID();
    UUID held = UUID.randomUUID();
    // Held first: after a completed transfer to this recipient the hold amount scores one less.
    assertThat(send(sender, recipient, HOLD, held).getStatus()).isEqualTo(201);
    assertThat(send(sender, recipient, ALLOW, completed).getStatus()).isEqualTo(201);
    assertThat(transferEmails(sender))
        .extracting(Row::kind)
        .containsExactly("TRANSFER_HELD", "TRANSFER_SENT");
    assertThat(transferEmails(recipient)).hasSize(1);

    // In the key's window: replays, and 409 for another body.
    assertThat(send(sender, recipient, ALLOW, completed).getStatus()).isEqualTo(201);
    assertThat(send(sender, recipient, HOLD, held).getStatus()).isEqualTo(201);
    assertThat(errorCode(send(sender, recipient, "5.00", completed)))
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    // Q14: the records are gone; the transfer rows answer.
    jdbc.update(
        "DELETE FROM transfer.idempotency_records WHERE customer_id = ?", (Object) sender.id());
    assertThat(send(sender, recipient, ALLOW, completed).getStatus()).isEqualTo(201);
    assertThat(send(sender, recipient, HOLD, held).getStatus()).isEqualTo(201);
    assertThat(errorCode(send(sender, recipient, "5.00", held)))
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");

    assertThat(transferEmails(sender)).hasSize(2);
    assertThat(transferEmails(recipient)).hasSize(1);
  }

  /**
   * A failure injected after an email row is written (the receiver's, after the sender's, on
   * completion; the sender's on a hold) rolls back the transfer, the posting and every email row.
   */
  @Test
  void aRolledBackTransferSendsNoEmail() throws Exception {
    Account sender = funded("5000.00");
    Account recipient = withWallet();
    doAnswer(callRealThen(new IllegalStateException("injected failure")))
        .when(notificationRowsSpy)
        .insert(any(), eq(recipient.id()), eq("TRANSFER_RECEIVED"), any(), any());

    MockHttpServletResponse completed = send(sender, recipient, ALLOW, UUID.randomUUID());

    assertThat(completed.getStatus()).isEqualTo(500);
    assertThat(transferEmails(sender)).as("the sender's row rolled back too").isEmpty();
    doAnswer(callRealThen(new IllegalStateException("injected failure")))
        .when(notificationRowsSpy)
        .insert(any(), eq(sender.id()), eq("TRANSFER_HELD"), any(), any());

    MockHttpServletResponse held = send(sender, recipient, HOLD, UUID.randomUUID());

    assertThat(held.getStatus()).isEqualTo(500);
    assertThat(transfersOf(sender)).isEmpty();
    assertBalances(sender, "5000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferEmails(sender)).isEmpty();
    assertThat(transferEmails(recipient)).isEmpty();
  }
}
