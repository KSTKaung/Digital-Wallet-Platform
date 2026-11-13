package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.portfolio.wallet.transfer.application.TransferExpiryService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * No log line and no customer response of the transfer paths carries the description, a risk score
 * or rule, a wallet number, a party's id or the {@code Idempotency-Key} (documentations/08 §4C.4
 * criterion 20; doc 06 MVP-ADR-20, -24 "Security implications"). Every outcome is driven, the
 * refusals and an injected risk failure included, because those are the paths that log.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferLogsIT extends TransferReviewFlowSupport {

  private static final String DESCRIPTION = "note-5b1d private";

  @Autowired TransferExpiryService expiry;

  @Test
  void transferPathsLogNoDescriptionScoreRuleNumberOrKey(CapturedOutput output) throws Exception {
    Account sender = funded("5000.00");
    Account recipient = withWallet();
    Account other = withWallet();
    String number = numberOf(recipient);
    List<UUID> keys = new ArrayList<>();
    List<MockHttpServletResponse> responses = new ArrayList<>();
    int start = output.getAll().length();

    // Held, replayed, reused key, blocked, completed, refused, not found, self, cancelled.
    UUID heldKey = UUID.randomUUID();
    keys.add(heldKey);
    responses.add(send(heldKey, number, HOLD, sender));
    responses.add(send(heldKey, number, HOLD, sender));
    responses.add(send(heldKey, number, ALLOW, sender));
    UUID blockedKey = UUID.randomUUID();
    keys.add(blockedKey);
    responses.add(send(blockedKey, numberOf(other), BLOCK, sender));
    UUID completedKey = UUID.randomUUID();
    keys.add(completedKey);
    responses.add(send(completedKey, number, "10.00", sender));
    UUID refusedKey = UUID.randomUUID();
    keys.add(refusedKey);
    responses.add(send(refusedKey, number, "4999.00", sender));
    responses.add(send(UUID.randomUUID(), absentNumber(), "10.00", sender));
    responses.add(send(UUID.randomUUID(), numberOf(sender), "10.00", sender));
    UUID held = transferId(responses.get(0));
    responses.add(cancel(held, sender));
    responses.add(cancel(held, sender));
    responses.add(getAs(TRANSFERS, sender.token()));
    responses.add(getAs(TRANSFERS + "/" + held, sender.token()));
    // An injected risk failure, which logs at ERROR.
    UUID failedKey = UUID.randomUUID();
    keys.add(failedKey);
    doThrow(new DataIntegrityViolationException("injected")).when(riskRowsSpy).lockSender(any());
    responses.add(send(failedKey, number, "10.00", sender));

    String logged = output.getAll().substring(start);
    assertThat(logged).as("captured output").isNotEmpty();
    List<String> secrets = new ArrayList<>();
    secrets.add(DESCRIPTION);
    secrets.add("note-5b1d");
    secrets.add(number);
    secrets.add(numberOf(sender));
    secrets.add(numberOf(other));
    secrets.add(sender.id().toString());
    secrets.add(recipient.id().toString());
    secrets.add(walletId(sender).toString());
    secrets.add(walletId(recipient).toString());
    secrets.add("TRANSFER_AMOUNT");
    secrets.add("NEW_RECIPIENT");
    secrets.add("NEW_SENDER_WALLET");
    secrets.add("TRANSFER_FREQUENCY");
    keys.forEach(key -> secrets.add(key.toString()));
    for (String secret : secrets) {
      assertThat(logged).as("log must not contain a sensitive value").doesNotContain(secret);
    }
    assertThat(logged).doesNotContainIgnoringCase("\"score\"");

    for (MockHttpServletResponse response : responses) {
      String content = response.getContentAsString();
      assertThat(content)
          .doesNotContain("note-5b1d")
          .doesNotContain("score")
          .doesNotContain("TRANSFER_AMOUNT")
          .doesNotContain("NEW_RECIPIENT")
          .doesNotContain("NEW_SENDER_WALLET")
          .doesNotContain("TRANSFER_FREQUENCY")
          .doesNotContain("description")
          .doesNotContain(number);
    }
  }

  /**
   * The paths the first test does not drive (PR 5a step 7): the status endpoint, the receiver's
   * list, detail and status, the expiry job (one success and one failure, which logs), a cancel and
   * a completion rolled back by a failure in their own transaction (which log at ERROR), and a
   * replayed hold. No log line and no response carries the description, a score, a rule code, a
   * wallet number, a party's id or the key.
   */
  @Test
  void statusReceiverReadsExpiryAndRollbacksLogAndReturnNothingSensitive(CapturedOutput output)
      throws Exception {
    Account sender = funded("5000.00");
    Account recipient = withWallet();
    String number = numberOf(recipient);
    List<UUID> keys = new ArrayList<>();
    List<MockHttpServletResponse> responses = new ArrayList<>();
    int start = output.getAll().length();

    UUID completedKey = UUID.randomUUID();
    keys.add(completedKey);
    MockHttpServletResponse completed = send(completedKey, number, ALLOW, sender);
    responses.add(completed);
    UUID completedId = transferId(completed);
    UUID heldKey = UUID.randomUUID();
    keys.add(heldKey);
    MockHttpServletResponse held = send(heldKey, number, "1400.00", sender);
    responses.add(held);
    responses.add(send(heldKey, number, "1400.00", sender));
    UUID heldId = transferId(held);
    UUID expiringKey = UUID.randomUUID();
    keys.add(expiringKey);
    UUID expiringId = transferId(send(expiringKey, number, "1400.00", sender));
    assertThat(transferRow(heldId)).containsEntry("status", "PENDING_REVIEW");
    assertThat(transferRow(expiringId)).containsEntry("status", "PENDING_REVIEW");
    // Status as the sender; list, detail and status as the receiver (held is the identical 404).
    for (UUID id : List.of(completedId, heldId)) {
      responses.add(getAs(TRANSFERS + "/" + id + "/status", sender.token()));
      responses.add(getAs(TRANSFERS + "/" + id, recipient.token()));
      responses.add(getAs(TRANSFERS + "/" + id + "/status", recipient.token()));
    }
    assertThat(getAs(TRANSFERS + "/" + heldId, recipient.token()).getStatus()).isEqualTo(404);
    responses.add(getAs(TRANSFERS, recipient.token()));
    // A cancel and a completion that fail inside their transaction: 500, logged at ERROR.
    doThrow(new DataIntegrityViolationException("injected")).when(auditRowsSpy).insert(any());
    MockHttpServletResponse failedCancel = cancel(heldId, sender);
    assertThat(failedCancel.getStatus()).isEqualTo(500);
    responses.add(failedCancel);
    UUID failedKey = UUID.randomUUID();
    keys.add(failedKey);
    MockHttpServletResponse failedCreate = send(failedKey, number, ALLOW, sender);
    assertThat(failedCreate.getStatus()).isEqualTo(500);
    responses.add(failedCreate);
    org.mockito.Mockito.reset(auditRowsSpy);
    responses.add(cancel(heldId, sender));
    // The expiry job: one failure (logged by id and class), then the success.
    overdue(expiringId, 60);
    doThrow(new IllegalStateException("injected"))
        .when(transferRowsSpy)
        .updateDecision(eq(expiringId), any(), any(), any(), anyLong());
    expiry.expireDue();
    org.mockito.Mockito.reset(transferRowsSpy);
    expiry.expireDue();
    responses.add(getAs(TRANSFERS + "/" + expiringId, sender.token()));
    responses.add(getAs(TRANSFERS + "/" + expiringId + "/status", sender.token()));
    responses.add(getAs(TRANSFERS, sender.token()));

    assertThat(transferRow(expiringId)).containsEntry("status", "EXPIRED");
    String logged = output.getAll().substring(start);
    assertThat(logged).contains("TRANSFER_EXPIRY_FAILED");
    List<String> secrets = new ArrayList<>();
    secrets.add("note-5b1d");
    secrets.add(number);
    secrets.add(numberOf(sender));
    secrets.add(sender.id().toString());
    secrets.add(recipient.id().toString());
    secrets.add(walletId(sender).toString());
    secrets.add(walletId(recipient).toString());
    secrets.add("TRANSFER_AMOUNT");
    secrets.add("NEW_RECIPIENT");
    secrets.add("NEW_SENDER_WALLET");
    secrets.add("TRANSFER_FREQUENCY");
    keys.forEach(key -> secrets.add(key.toString()));
    for (String secret : secrets) {
      assertThat(logged).as("log must not contain a sensitive value").doesNotContain(secret);
    }
    assertThat(logged).doesNotContainIgnoringCase("\"score\"");
    for (MockHttpServletResponse response : responses) {
      assertThat(response.getContentAsString())
          .doesNotContain("note-5b1d")
          .doesNotContain("score")
          .doesNotContain("TRANSFER_AMOUNT")
          .doesNotContain("NEW_RECIPIENT")
          .doesNotContain("NEW_SENDER_WALLET")
          .doesNotContain("TRANSFER_FREQUENCY")
          .doesNotContain("description")
          .doesNotContain("riskAssessment")
          .doesNotContain(number)
          .doesNotContain(numberOf(sender));
    }
  }

  private MockHttpServletResponse send(UUID key, String number, String amount, Account sender)
      throws Exception {
    return postTransfer(transferBody(number, amount, DESCRIPTION), sender.token(), key, randomIp());
  }
}
