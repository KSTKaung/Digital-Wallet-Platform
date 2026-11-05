package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Failure injection in the <b>second</b> transaction of a refused movement, the one that stores the
 * refusal and its {@code FAILURE} audit row (doc 02 §1.4 "the rejection is stored in its own
 * transaction"; doc 06 MVP-ADR-03 amendment, W5; doc 08 §4B.2 owner requirement 2). Whatever step
 * fails, the client gets {@code 500 SYSTEM_ERROR}, neither the record nor the audit row is left
 * behind, no money moved, and a retry with the same key runs fresh: it is not a replay and not a
 * {@code 409 IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>{@link MoneyFailureIT} covers failures inside the money transaction itself.
 */
class MoneyRefusalStorageFailureIT extends MoneyFlowSupport {

  enum Step {
    /** The insert of the refusal's idempotency record fails. */
    RECORD_INSERT,
    /** The refusal's record is written, then the call fails. */
    AFTER_RECORD_INSERT,
    /**
     * The refusal's audit row is written, then the call fails: the last step of the transaction.
     */
    AFTER_AUDIT_ROW
  }

  private static Answer<Object> callRealThen(RuntimeException failure) {
    return invocation -> {
      invocation.callRealMethod();
      throw failure;
    };
  }

  private void failAt(Step step, String action) {
    RuntimeException injected = new IllegalStateException("injected failure");
    switch (step) {
      // A non-null status is only ever passed when a refusal is stored.
      case RECORD_INSERT ->
          doThrow(injected)
              .when(idempotencyRowsSpy)
              .insertIfAbsent(
                  any(), anyString(), any(), anyString(), notNull(), any(), any(), any(), any());
      case AFTER_RECORD_INSERT ->
          doAnswer(callRealThen(injected))
              .when(idempotencyRowsSpy)
              .insertIfAbsent(
                  any(), anyString(), any(), anyString(), notNull(), any(), any(), any(), any());
      case AFTER_AUDIT_ROW ->
          doAnswer(callRealThen(injected))
              .when(auditRowsSpy)
              .insert(
                  argThat(
                      row ->
                          row != null
                              && row.action().equals(action)
                              && row.result().equals("FAILURE")));
    }
  }

  private int failureAudits(UUID customerId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs"
            + " WHERE actor_id = ? AND action IN ('DEPOSIT', 'WITHDRAWAL') AND result = 'FAILURE'",
        Integer.class,
        customerId);
  }

  private void assertNothingStored(MockHttpServletResponse failed, Account customer, UUID key)
      throws Exception {
    assertThat(failed.getStatus()).isEqualTo(500);
    assertThat(errorCode(failed)).isEqualTo("SYSTEM_ERROR");
    assertThat(failed.getContentAsString()).doesNotContain("injected");
    assertThat(idempotencyRecords(customer.id(), key)).isZero();
    assertThat(failureAudits(customer.id())).isZero();
  }

  /**
   * An insufficient-balance refusal whose storage fails; after a top-up the same key and body
   * withdraw: the key was never consumed.
   */
  @ParameterizedTest
  @EnumSource(Step.class)
  void aWithdrawalRefusalThatFailsToBeStoredLeavesTheKeyFree(Step step) throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();
    failAt(step, "WITHDRAWAL");

    MockHttpServletResponse failed = withdraw(customer, "500.00", key);

    assertNothingStored(failed, customer, key);
    assertThat(available(wallet)).isEqualByComparingTo("0.00");
    assertThat(ledgerTransactions(wallet)).isZero();
    assertThat(rows("wallet.withdrawals", wallet)).isZero();
    assertThat(usedToday(wallet, "WITHDRAWAL")).isEqualByComparingTo("0");

    reset(idempotencyRowsSpy, auditRowsSpy);
    funded(customer, "1000.00");
    MockHttpServletResponse retry = withdraw(customer, "500.00", key);

    assertThat(retry.getStatus()).isEqualTo(201);
    assertThat(available(wallet)).isEqualByComparingTo("500.00");
    assertThat(idempotencyRecords(customer.id(), key)).isEqualTo(1);
    assertThat(failureAudits(customer.id())).isZero();
    assertReconciled(wallet);
  }

  /**
   * A single-limit refusal whose storage fails; the retry with the same key is refused again, and
   * this time it is stored with its one audit row. A replay would write no audit row, so the row
   * proves the retry ran fresh.
   */
  @ParameterizedTest
  @EnumSource(Step.class)
  void aDepositRefusalThatFailsToBeStoredIsDecidedAfreshOnTheRetry(Step step) throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();
    failAt(step, "DEPOSIT");

    MockHttpServletResponse failed = deposit(customer, "100000.01", key);

    assertNothingStored(failed, customer, key);
    assertThat(available(wallet)).isEqualByComparingTo("0.00");

    reset(idempotencyRowsSpy, auditRowsSpy);
    MockHttpServletResponse retry = deposit(customer, "100000.01", key);

    assertThat(retry.getStatus()).isEqualTo(409);
    assertThat(errorCode(retry)).isEqualTo("SINGLE_LIMIT_EXCEEDED");
    assertThat(idempotencyRecords(customer.id(), key)).isEqualTo(1);
    assertThat(failureAudits(customer.id())).isEqualTo(1);
    // From now on it is a stored refusal: replayed, not audited again.
    assertThat(errorCode(deposit(customer, "100000.01", key))).isEqualTo("SINGLE_LIMIT_EXCEEDED");
    assertThat(failureAudits(customer.id())).isEqualTo(1);
    assertThat(available(wallet)).isEqualByComparingTo("0.00");
  }

  /** A frozen-wallet refusal whose storage fails; after the unfreeze the same key deposits. */
  @ParameterizedTest
  @EnumSource(Step.class)
  void aFrozenRefusalThatFailsToBeStoredDoesNotOutliveTheFreeze(Step step) throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    assertThat(postAs(FREEZE, null, customer.token()).getStatus()).isEqualTo(200);
    UUID key = UUID.randomUUID();
    failAt(step, "DEPOSIT");

    assertNothingStored(deposit(customer, "300.00", key), customer, key);

    reset(idempotencyRowsSpy, auditRowsSpy);
    String admin = account(com.portfolio.wallet.auth.domain.Role.ADMIN).token();
    assertThat(
            postAs(
                    "/api/v1/admin/customers/" + customer.id() + "/unfreeze",
                    java.util.Map.of("reason", "Customer confirmed by phone"),
                    admin)
                .getStatus())
        .isEqualTo(200);

    assertThat(deposit(customer, "300.00", key).getStatus()).isEqualTo(201);
    assertThat(available(wallet)).isEqualByComparingTo("300.00");
    assertReconciled(wallet);
  }
}
