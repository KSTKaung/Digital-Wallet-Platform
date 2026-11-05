package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.portfolio.wallet.wallet.persistence.MoneyMovementRepository.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A failure injected after each step of the money transaction rolls the whole movement back
 * (documentations/06 MVP-ADR-03; doc 08 §4B.4 AC 5; owner requirement 2): balance, day's usage,
 * ledger, business row, audit row and idempotency record are all unchanged, the client gets {@code
 * 500 SYSTEM_ERROR}, and a retry with the same key runs fresh and succeeds.
 */
class MoneyFailureIT extends MoneyFlowSupport {

  enum Step {
    /** The snapshot and usage are updated; the ledger header insert fails. */
    AFTER_SNAPSHOT_UPDATE,
    /** The ledger transaction and both entries are written; the business row insert fails. */
    AFTER_LEDGER_POSTING,
    /** The deposit or withdrawal row is written, then the call fails. */
    AFTER_BUSINESS_ROW,
    /** The audit row is written, then the call fails: the last step before the response. */
    AFTER_AUDIT_ROW
  }

  // Failures are injected in the repositories: the transactional services' proxies cannot be
  // stubbed outside a transaction (their propagation is MANDATORY).
  private void failAt(Step step, boolean deposit) {
    RuntimeException injected = new IllegalStateException("injected failure");
    Table table = deposit ? Table.DEPOSITS : Table.WITHDRAWALS;
    switch (step) {
      case AFTER_SNAPSHOT_UPDATE ->
          doThrow(injected)
              .when(ledgerRowsSpy)
              .insertTransaction(any(), any(), any(), any(), any());
      case AFTER_LEDGER_POSTING ->
          doThrow(injected).when(movementRowsSpy).insert(eq(table), any(), any());
      case AFTER_BUSINESS_ROW ->
          doAnswer(callRealThen(injected)).when(movementRowsSpy).insert(eq(table), any(), any());
      case AFTER_AUDIT_ROW ->
          doAnswer(callRealThen(injected))
              .when(auditRowsSpy)
              .insert(
                  argThat(
                      row ->
                          row != null && row.action().equals(deposit ? "DEPOSIT" : "WITHDRAWAL")));
    }
  }

  private static org.mockito.stubbing.Answer<Object> callRealThen(RuntimeException failure) {
    return invocation -> {
      invocation.callRealMethod();
      throw failure;
    };
  }

  @ParameterizedTest
  @EnumSource(Step.class)
  void aDepositThatFailsAtAnyStepLeavesNothing(Step step) throws Exception {
    assertRolledBack(step, true);
  }

  @ParameterizedTest
  @EnumSource(Step.class)
  void aWithdrawalThatFailsAtAnyStepLeavesNothing(Step step) throws Exception {
    assertRolledBack(step, false);
  }

  private void assertRolledBack(Step step, boolean deposit) throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000.00");
    BigDecimal usedBefore = usedToday(wallet, deposit ? "DEPOSIT" : "WITHDRAWAL");
    int auditBefore = moneyAuditRows(customer.id());
    UUID key = UUID.randomUUID();
    failAt(step, deposit);

    MockHttpServletResponse failed =
        deposit ? deposit(customer, "300.00", key) : withdraw(customer, "300.00", key);

    assertThat(failed.getStatus()).isEqualTo(500);
    assertThat(errorCode(failed)).isEqualTo("SYSTEM_ERROR");
    assertThat(failed.getContentAsString()).doesNotContain("injected");
    assertThat(available(wallet)).isEqualByComparingTo("1000.00");
    assertThat(usedToday(wallet, deposit ? "DEPOSIT" : "WITHDRAWAL"))
        .isEqualByComparingTo(usedBefore);
    assertThat(ledgerTransactions(wallet)).isEqualTo(1);
    assertThat(rows(deposit ? "wallet.deposits" : "wallet.withdrawals", wallet))
        .isEqualTo(deposit ? 1 : 0);
    assertThat(moneyAuditRows(customer.id())).isEqualTo(auditBefore);
    assertThat(idempotencyRecords(customer.id(), key)).isZero();
    assertReconciled(wallet);

    org.mockito.Mockito.reset(ledgerRowsSpy, movementRowsSpy, auditRowsSpy);
    MockHttpServletResponse retry =
        deposit ? deposit(customer, "300.00", key) : withdraw(customer, "300.00", key);
    assertThat(retry.getStatus()).isEqualTo(201);
    assertThat(available(wallet)).isEqualByComparingTo(deposit ? "1300.00" : "700.00");
    assertReconciled(wallet);
  }
}
