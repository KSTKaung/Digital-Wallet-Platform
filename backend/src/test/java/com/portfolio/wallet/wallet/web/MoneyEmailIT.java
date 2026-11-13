package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.notification.application.OutboxDispatcher;
import com.portfolio.wallet.notification.application.RecordingEmailSender;
import com.portfolio.wallet.support.OutboxRows;
import com.portfolio.wallet.support.OutboxRows.Row;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Deposit and withdrawal emails (documentations/06 MVP-ADR-24 amendment "Emails (Q9)", MVP-ADR-25;
 * doc 08 §4C.2 Q9, §4C.4 criterion 19): one outbox row for the customer in the movement's
 * transaction, with the amount, the currency and the server reference only, sent after the commit;
 * nothing for a replay (in the key's window or after it, Q14), a {@code 409}, a stored refusal or a
 * rollback.
 */
class MoneyEmailIT extends MoneyFlowSupport {

  private static final String DEPOSIT = EmailKind.DEPOSIT_COMPLETED.name();
  private static final String WITHDRAWAL = EmailKind.WITHDRAWAL_COMPLETED.name();

  @Autowired OutboxDispatcher dispatcher;
  @Autowired RecordingEmailSender mail;

  private List<Row> moneyEmails(Account customer) {
    return OutboxRows.of(jdbc, customer.id(), DEPOSIT, WITHDRAWAL);
  }

  /** Sends the row now and returns the message the customer received. */
  private EmailMessage delivered(Account customer, Row row) {
    assertThat(dispatcher.dispatch(row.eventId())).as("claimed").isTrue();
    String address = "user-" + customer.id() + "@example.test";
    return mail.messages().stream()
        .filter(m -> m.to().equals(address) && m.kind().name().equals(row.kind()))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  @Test
  void aDepositAndAWithdrawalEachEmailTheCustomerWithAmountCurrencyAndReferenceOnly()
      throws Exception {
    Account customer = withWallet();
    String walletNumber = (String) walletOf(customer.id()).get("wallet_number");

    MockHttpServletResponse deposit = deposit(customer, "1500.5", UUID.randomUUID());
    MockHttpServletResponse withdrawal = withdraw(customer, "200", UUID.randomUUID());

    assertThat(deposit.getStatus()).isEqualTo(201);
    assertThat(withdrawal.getStatus()).isEqualTo(201);
    String depositReference = body(deposit).get("reference").asString();
    String withdrawalReference = body(withdrawal).get("reference").asString();
    List<Row> rows = moneyEmails(customer);
    assertThat(rows).extracting(Row::kind).containsExactly(DEPOSIT, WITHDRAWAL);
    assertThat(rows.get(0).values())
        .isEqualTo(Map.of("amount", "1500.50", "currency", "THB", "reference", depositReference));
    assertThat(rows.get(1).values())
        .isEqualTo(Map.of("amount", "200.00", "currency", "THB", "reference", withdrawalReference));

    EmailMessage deposited = delivered(customer, rows.get(0));
    assertThat(deposited.subject()).isEqualTo("Your deposit was completed");
    assertThat(deposited.body()).contains("1,500.50 THB", depositReference, "(Asia/Bangkok time)");
    EmailMessage withdrawn = delivered(customer, rows.get(1));
    assertThat(withdrawn.subject()).isEqualTo("Your withdrawal was completed");
    assertThat(withdrawn.body()).contains("200.00 THB", withdrawalReference);
    for (EmailMessage message : List.of(deposited, withdrawn)) {
      // Never the balance (1,300.50 after the withdrawal), the wallet number or the deposit's note.
      assertThat(message.subject() + " " + message.body())
          .doesNotContain(walletNumber, "1300.50", "1,300.50", "salary", "balance", "Somchai");
    }
    assertThat(OutboxRows.of(jdbc, customer.id(), DEPOSIT, WITHDRAWAL))
        .extracting(Row::status)
        .containsOnly("SENT");
  }

  @Test
  void aReplayAKeyReuseAndAReplayAfterTheRecordIsGoneWriteNoEmail() throws Exception {
    Account customer = withWallet();
    UUID key = UUID.randomUUID();
    assertThat(deposit(customer, "400.00", key).getStatus()).isEqualTo(201);
    assertThat(moneyEmails(customer)).hasSize(1);

    // In the key's window: the same request is replayed, another one is 409.
    assertThat(deposit(customer, "400", key).getStatus()).isEqualTo(201);
    MockHttpServletResponse reused = deposit(customer, "400.01", key);
    assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(moneyEmails(customer)).hasSize(1);

    // Q14: after the purge, and after 24 hours, the deposit row answers; no new email.
    jdbc.update(
        "DELETE FROM wallet.idempotency_records WHERE customer_id = ? AND idempotency_key = ?",
        customer.id(),
        key);
    assertThat(deposit(customer, "400.00", key).getStatus()).isEqualTo(201);
    mutableClock.advance(Duration.ofHours(24).plusSeconds(1));
    customer = signedInAgain(customer);
    assertThat(deposit(customer, "400.00", key).getStatus()).isEqualTo(201);
    assertThat(errorCode(deposit(customer, "401.00", key))).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    assertThat(moneyEmails(customer)).hasSize(1);
  }

  @Test
  void aStoredRefusalAndItsReplayWriteNoEmail() throws Exception {
    Account customer = withWallet();
    funded(customer, "300.00");
    UUID key = UUID.randomUUID();

    assertThat(errorCode(withdraw(customer, "500.00", key))).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(errorCode(withdraw(customer, "500.00", key))).isEqualTo("INSUFFICIENT_BALANCE");

    assertThat(moneyEmails(customer)).extracting(Row::kind).containsExactly(DEPOSIT);
  }

  /** The row is written before the response is stored; a failure there rolls it back too. */
  @Test
  void aRolledBackMovementLeavesNoEmail() throws Exception {
    Account customer = withWallet();
    funded(customer, "1000.00");
    doThrow(new IllegalStateException("injected failure"))
        .when(idempotencyRowsSpy)
        .complete(any(), anyString(), any(), anyInt(), anyString());

    MockHttpServletResponse deposit = deposit(customer, "300.00", UUID.randomUUID());
    MockHttpServletResponse withdrawal = withdraw(customer, "300.00", UUID.randomUUID());

    assertThat(deposit.getStatus()).isEqualTo(500);
    assertThat(withdrawal.getStatus()).isEqualTo(500);
    assertThat(available(walletId(customer))).isEqualByComparingTo("1000.00");
    assertThat(moneyEmails(customer)).extracting(Row::kind).containsExactly(DEPOSIT);
  }
}
