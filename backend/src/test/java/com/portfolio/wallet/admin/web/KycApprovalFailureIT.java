package com.portfolio.wallet.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.portfolio.wallet.wallet.application.WalletNumberGenerator;
import com.portfolio.wallet.wallet.application.WalletProperties;
import com.portfolio.wallet.wallet.domain.WalletNumber;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Failures inside the approval transaction (documentations/08 §4B.4 AC 3: "a failure leaves nothing
 * behind"; doc 06 MVP-ADR-03 rollback test pattern) and the wallet-number retry (MVP-ADR-20).
 */
@ExtendWith(OutputCaptureExtension.class)
class KycApprovalFailureIT extends AdminFlowSupport {

  @Autowired WalletProperties walletProperties;

  @Test
  void aLedgerFailureAfterItsInsertRollsBackTheWholeApproval() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    AtomicReference<UUID> insertedAccount = new AtomicReference<>();
    doAnswer(
            invocation -> {
              insertedAccount.set((UUID) invocation.callRealMethod());
              throw new IllegalStateException("forced ledger failure");
            })
        .when(spied(ledgerAccounts))
        .openCustomerAccount(any());

    MockHttpServletResponse response = approve(application, officer().token());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(errorCode(response)).isEqualTo("SYSTEM_ERROR");
    assertThat(response.getContentAsString()).doesNotContain("forced");
    assertThat(insertedAccount.get()).as("the account insert ran").isNotNull();
    assertNothingLeftBehind(customer, application);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_accounts WHERE id = ?",
                Integer.class,
                insertedAccount.get()))
        .isZero();
  }

  @Test
  void aTakenWalletNumberIsReplacedAndTheApprovalSucceeds() throws Exception {
    WalletNumber taken = new WalletNumberGenerator().next();
    insert("wallet.wallets", with(validWallet(), "wallet_number", taken.value()));
    Account customer = customer();
    UUID application = pending(customer);
    doReturn(taken).doCallRealMethod().when(spied(numbers)).next();

    MockHttpServletResponse response = approve(application, officer().token());

    assertThat(response.getStatus()).isEqualTo(200);
    verify(spied(numbers), times(2)).next();
    List<Map<String, Object>> wallets = walletsOf(customer.id());
    assertThat(wallets).hasSize(1);
    assertThat(wallets.getFirst().get("wallet_number")).isNotEqualTo(taken.value());
    assertThat(ledgerAccountsOf((UUID) wallets.getFirst().get("id"))).hasSize(1);
  }

  /**
   * Owner follow-up #4: the documented 500 with the generic body, a log line without customer data,
   * and nothing created: no wallet, no ledger account, no legal-name copy, no audit row, no email,
   * and the application still {@code PENDING_REVIEW} (MVP-ADR-10 amendment).
   */
  @Test
  void whenEveryDrawnNumberIsTakenTheApprovalFailsAndLeavesNothing(CapturedOutput output)
      throws Exception {
    WalletNumber taken = new WalletNumberGenerator().next();
    insert("wallet.wallets", with(validWallet(), "wallet_number", taken.value()));
    Account customer = customer();
    UUID application = pending(customer);
    doReturn(taken).when(spied(numbers)).next();
    int logStart = output.getOut().length();

    MockHttpServletResponse response = approve(application, officer().token());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(errorCode(response)).isEqualTo("SYSTEM_ERROR");
    JsonNode body = body(response);
    assertThat(body.get("message").asString()).isEqualTo("An unexpected error occurred");
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder("success", "errorCode", "message", "correlationId", "timestamp");
    assertThat(response.getContentAsString()).doesNotContain("attempt", "wallet number");
    verify(spied(numbers), times(walletProperties.walletNumberAttempts())).next();
    verify(spied(ledgerAccounts), never()).openCustomerAccount(any());
    assertNothingLeftBehind(customer, application);

    String log = output.getOut().substring(logStart);
    assertThat(log).contains("Unhandled exception", "no unused wallet number after");
    assertThat(log)
        .doesNotContain(
            customer.id().toString(),
            customer.email(),
            application.toString(),
            taken.value(),
            FIRST_NAME,
            LAST_NAME,
            REFERENCE);
  }

  /**
   * The email is sent after the commit from the outbox (MVP-ADR-25), so an SMTP failure cannot undo
   * a decision: it only counts an attempt on the outbox row.
   */
  @Test
  void aMailFailureDoesNotUndoTheApproval() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    mail.setFailing(true);
    try {
      assertThat(approve(application, officer().token()).getStatus()).isEqualTo(200);
      deliverOutbox();
    } finally {
      mail.setFailing(false);
    }

    assertThat(application(application)).containsEntry("status", "APPROVED");
    assertThat(walletsOf(customer.id())).hasSize(1);
    assertThat(outboxRows(customer.id()))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .containsEntry("kind", "KYC_APPROVED")
                    .containsEntry("status", "PENDING")
                    .containsEntry("attempts", 1));
  }

  /** A rolled-back approval leaves no outbox row, so nothing is ever sent for it. */
  private void assertNoOutboxRow(Account customer) {
    assertThat(outboxRows(customer.id())).isEmpty();
  }

  private void assertNothingLeftBehind(Account customer, UUID application) throws Exception {
    assertThat(application(application))
        .containsEntry("status", "PENDING_REVIEW")
        .containsEntry("reviewer_id", null)
        .containsEntry("decided_at", null);
    assertThat(profile(customer.id()))
        .containsEntry("first_name", null)
        .containsEntry("last_name", null);
    assertThat(walletsOf(customer.id())).isEmpty();
    assertThat(auditRows(application.toString()))
        .noneMatch(r -> "KYC_APPLICATION_APPROVE".equals(r.get("action")));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'WALLET_CREATE'"
                    + " AND new_value ->> 'customerId' = ?",
                Integer.class,
                customer.id().toString()))
        .isZero();
    assertNoOutboxRow(customer);
    assertNoMailTo(customer.email());
    // The application can still be decided afterwards.
    assertThat(body(getAs(REVIEW + "/" + application, officer().token())).get("status").asString())
        .isEqualTo("PENDING_REVIEW");
  }
}
