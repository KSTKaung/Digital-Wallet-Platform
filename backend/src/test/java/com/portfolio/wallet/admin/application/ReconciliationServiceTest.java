package com.portfolio.wallet.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.admin.application.ReconciliationMismatch.Kind;
import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads.AccountTotals;
import com.portfolio.wallet.risk.application.RiskReconciliationReads;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.WalletBalances;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One reconciliation run (documentations/06 MVP-ADR-03; MVP-ADR-24 "Jobs (Q11)"; doc 08 §4C.4
 * criterion 18): one read-only {@code REPEATABLE READ} transaction with the D14 timeouts first,
 * then every module's reads; each mismatch at ERROR under the alert key with ids only, a summary at
 * INFO, and a failure logged by class only without throwing.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationServiceTest {

  private final WalletReconciliationReads wallets = mock(WalletReconciliationReads.class);
  private final TransferReconciliationReads transfers = mock(TransferReconciliationReads.class);
  private final RiskReconciliationReads reviews = mock(RiskReconciliationReads.class);
  private final LedgerReconciliationReads ledger = mock(LedgerReconciliationReads.class);
  private final TransactionTimeouts timeouts = mock(TransactionTimeouts.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  private final ReconciliationService service =
      new ReconciliationService(wallets, transfers, reviews, ledger, timeouts, transactions);

  @BeforeEach
  void transactionManager() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  }

  @AfterEach
  void noLeftOverSynchronization() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void readsEveryModuleInOneReadOnlyRepeatableReadTransactionWithTheTimeoutsFirst() {
    ReconciliationReport report = service.reconcile();

    assertThat(report.completed()).isTrue();
    assertThat(report.mismatches()).isEmpty();
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().isReadOnly()).isTrue();
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    InOrder order = inOrder(transactions, timeouts, wallets, transfers, reviews, ledger);
    order.verify(transactions).getTransaction(any());
    order.verify(timeouts).apply();
    order.verify(wallets).balances();
    order.verify(wallets).completedDeposits();
    order.verify(wallets).completedWithdrawals();
    order.verify(wallets).reservedUsage();
    order.verify(transfers).completed();
    order.verify(transfers).heldSums();
    order.verify(transfers).everHeld();
    order.verify(reviews).reviewOutcomes();
    order.verify(ledger).accountTotals();
    order.verify(ledger).postings();
    order.verify(transactions).commit(any());
  }

  @Test
  void eachMismatchIsLoggedAtErrorWithTheAlertKeyAndIdsOnlyAndTheRunIsSummarised(
      CapturedOutput output) {
    UUID wallet = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    when(wallets.balances())
        .thenReturn(
            List.of(new WalletBalances(wallet, new BigDecimal("123.45"), new BigDecimal("0.00"))));
    when(ledger.accountTotals())
        .thenReturn(
            List.of(
                new AccountTotals(account, wallet, BigDecimal.ZERO, new BigDecimal("678.90")),
                new AccountTotals(
                    UUID.randomUUID(), null, new BigDecimal("678.90"), BigDecimal.ZERO)));

    ReconciliationReport report = service.reconcile();

    assertThat(report.completed()).isTrue();
    assertThat(report.mismatches())
        .singleElement()
        .satisfies(m -> assertThat(m.kind()).isEqualTo(Kind.WALLET_BALANCE_DIFFERS));
    assertThat(report.rowsRead()).containsEntry("wallets", 1).containsEntry("ledgerAccounts", 2);
    assertThat(output.getOut())
        .contains(
            "ERROR",
            "RECONCILIATION_MISMATCH: check=1, kind=WALLET_BALANCE_DIFFERS, walletId="
                + wallet
                + ", ledgerAccountId="
                + account)
        .contains("Reconciliation run: mismatches=1, byCheck={1=1}")
        .contains("durationMs=")
        // Balances are Confidential: never logged.
        .doesNotContain("123.45")
        .doesNotContain("678.90");
  }

  @Test
  void aFailingReadIsLoggedByClassOnlyAndNothingIsCompared(CapturedOutput output) {
    when(transfers.everHeld())
        .thenThrow(new QueryTimeoutException("canceling statement due to statement timeout"));

    ReconciliationReport report = service.reconcile();

    assertThat(report.completed()).isFalse();
    assertThat(report.mismatches()).isEmpty();
    verify(ledger, never()).accountTotals();
    verify(transactions).rollback(any());
    assertThat(output.getOut())
        .contains(
            "RECONCILIATION_FAILED: reconciliation run did not complete:"
                + " failure=org.springframework.dao.QueryTimeoutException, sqlState=none")
        .doesNotContain("canceling statement")
        .doesNotContain("RECONCILIATION_MISMATCH");
  }

  @Test
  void anErrorThatIsNotAVirtualMachineErrorIsLoggedAndReturned(CapturedOutput output) {
    // A LinkageError: an Error, but not a VirtualMachineError.
    when(ledger.postings()).thenThrow(new NoClassDefFoundError("missing 7f3e"));

    ReconciliationReport report = service.reconcile();

    assertThat(report.completed()).isFalse();
    assertThat(report.mismatches()).isEmpty();
    verify(transactions).rollback(any());
    assertThat(output.getOut())
        .contains(
            "RECONCILIATION_FAILED: reconciliation run did not complete:"
                + " failure=java.lang.NoClassDefFoundError, sqlState=none")
        .doesNotContain("missing 7f3e");
  }

  /** OutOfMemoryError and StackOverflowError are both VirtualMachineErrors. */
  @ParameterizedTest
  @ValueSource(strings = {"OutOfMemoryError", "StackOverflowError"})
  void aVirtualMachineErrorIsLoggedAndThenRethrown(String name, CapturedOutput output) {
    VirtualMachineError fatal =
        name.equals("OutOfMemoryError")
            ? new OutOfMemoryError("detail 2c9a")
            : new StackOverflowError("detail 2c9a");
    when(wallets.balances()).thenThrow(fatal);

    assertThatThrownBy(service::reconcile).isSameAs(fatal);

    assertThat(output.getOut())
        .contains(
            "RECONCILIATION_FAILED: reconciliation run did not complete: failure=java.lang."
                + name
                + ", sqlState=none")
        .doesNotContain("detail 2c9a");
  }

  /**
   * A database failure as Spring translates it: the SQLException sits deeper in the cause chain.
   * The line has the full class name and the SQLState, never a message or the SQL text.
   */
  @Test
  void aDatabaseFailureLogsItsSqlStateButNeverItsMessageOrSql(CapturedOutput output) {
    java.sql.SQLException sql =
        new java.sql.SQLException(
            "ERROR: canceling statement due to lock timeout; customer 4b1e", "55P03");
    org.springframework.jdbc.UncategorizedSQLException translated =
        new org.springframework.jdbc.UncategorizedSQLException(
            "StatementCallback", "SELECT id FROM wallet.wallets WHERE secret_9d4f", sql);
    when(wallets.balances()).thenThrow(new IllegalStateException("wrapped 77aa", translated));

    assertThat(service.reconcile().completed()).isFalse();

    assertThat(output.getOut())
        .contains(
            "RECONCILIATION_FAILED: reconciliation run did not complete:"
                + " failure=java.lang.IllegalStateException, sqlState=55P03")
        .doesNotContain("lock timeout")
        .doesNotContain("4b1e")
        .doesNotContain("secret_9d4f")
        .doesNotContain("SELECT")
        .doesNotContain("wrapped 77aa")
        .doesNotContain("StatementCallback");
  }

  @Test
  void theSqlStateIsTheFirstInTheCauseChainOrNone() {
    java.sql.SQLException outer = new java.sql.SQLException("a", "57014");
    outer.initCause(new java.sql.SQLException("b", "55P03"));
    assertThat(ReconciliationService.sqlState(outer)).isEqualTo("57014");
    assertThat(ReconciliationService.sqlState(new java.sql.SQLException("no state")))
        .isEqualTo("none");
    assertThat(ReconciliationService.sqlState(new IllegalStateException("x"))).isEqualTo("none");
    // A cyclic chain ends: the cause of a is b, the cause of b is a.
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);
    assertThat(ReconciliationService.sqlState(a)).isEqualTo("none");
  }

  @Test
  void aFailingTransactionStartIsLoggedAndNothingIsRead(CapturedOutput output) {
    when(transactions.getTransaction(any())).thenThrow(new IllegalStateException("pool"));

    assertThat(service.reconcile().completed()).isFalse();

    verifyNoInteractions(timeouts, wallets, transfers, reviews, ledger);
    assertThat(output.getOut()).contains("failure=java.lang.IllegalStateException, sqlState=none");
  }

  @Test
  void refusesToRunInsideATransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(service::reconcile).isInstanceOf(IllegalTransactionStateException.class);

    verifyNoInteractions(transactions, timeouts, wallets);
  }

  @Test
  void theScheduleRunsTheReconciliation() {
    ReconciliationService reconciliation = mock(ReconciliationService.class);

    new ReconciliationSchedule(reconciliation).run();

    verify(reconciliation).reconcile();
  }

  @Test
  void propertiesNeedAPositiveInterval() {
    ReconciliationProperties properties =
        new ReconciliationProperties(true, Duration.ofMinutes(5), Duration.ofHours(1));
    assertThat(properties.initialDelay()).isEqualTo(Duration.ofMinutes(5));
    assertThat(properties.interval()).isEqualTo(Duration.ofHours(1));
    for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThatThrownBy(() -> new ReconciliationProperties(true, Duration.ofMinutes(5), bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("app.reconciliation.interval must be positive");
    }
  }

  @Test
  void theInitialDelayIsPositiveAndAtMostTheInterval() {
    // Equal to the interval is the longest allowed.
    assertThat(
            new ReconciliationProperties(false, Duration.ofHours(1), Duration.ofHours(1))
                .initialDelay())
        .isEqualTo(Duration.ofHours(1));
    for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThatThrownBy(() -> new ReconciliationProperties(true, bad, Duration.ofHours(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("app.reconciliation.initial-delay must be positive");
    }
    assertThatThrownBy(
            () ->
                new ReconciliationProperties(
                    true, Duration.ofHours(1).plusMillis(1), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "app.reconciliation.initial-delay must not be longer than app.reconciliation.interval");
  }

  /** The schedule's first run uses the initial delay, later runs the interval as a fixed delay. */
  @Test
  void theScheduleUsesTheInitialDelayThenTheIntervalAsAFixedDelay() throws Exception {
    org.springframework.scheduling.annotation.Scheduled scheduled =
        ReconciliationSchedule.class
            .getDeclaredMethod("run")
            .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

    assertThat(scheduled.initialDelayString()).isEqualTo("${app.reconciliation.initial-delay}");
    assertThat(scheduled.fixedDelayString()).isEqualTo("${app.reconciliation.interval}");
    assertThat(scheduled.fixedRateString()).isEmpty();
  }

  /** application.yaml binds 5 minutes and 1 hour, which pass the checks. */
  @Test
  void theConfiguredValuesBindAndPass() {
    org.springframework.boot.env.YamlPropertySourceLoader loader =
        new org.springframework.boot.env.YamlPropertySourceLoader();
    org.springframework.core.env.StandardEnvironment environment =
        new org.springframework.core.env.StandardEnvironment();
    try {
      loader
          .load(
              "application", new org.springframework.core.io.ClassPathResource("application.yaml"))
          .forEach(environment.getPropertySources()::addLast);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    ReconciliationProperties bound =
        org.springframework.boot.context.properties.bind.Binder.get(environment)
            .bind("app.reconciliation", ReconciliationProperties.class)
            .get();

    assertThat(bound.enabled()).isTrue();
    assertThat(bound.initialDelay()).isEqualTo(Duration.ofMinutes(5));
    assertThat(bound.interval()).isEqualTo(Duration.ofHours(1));
  }
}
