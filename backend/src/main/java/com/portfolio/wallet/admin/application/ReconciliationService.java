package com.portfolio.wallet.admin.application;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads;
import com.portfolio.wallet.risk.application.RiskReconciliationReads;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled reconciliation (documentations/06 MVP-ADR-03; MVP-ADR-24 "Jobs (Q11)"; doc 07 §5,
 * D18; doc 08 §4C.4 criterion 18). It only reads and logs: it never changes a balance, a ledger row
 * or a record (doc 07 §5 "No balance fixes"), so it is safe with several instances, each of which
 * logs what it finds.
 *
 * <h2>Checks</h2>
 *
 * The five checks of MVP-ADR-03 and Σ DEBIT = Σ CREDIT, per ledger transaction and over the whole
 * ledger; see {@link Reconciler} and {@link ReconciliationMismatch.Kind}.
 *
 * <h2>One snapshot</h2>
 *
 * Every module reads its own schema through its application service ({@code wallet}, {@code
 * transfer}, {@code risk}, {@code ledger}; no cross-schema SQL), and the comparison happens here,
 * in memory. All the reads run in <b>one read-only {@code REPEATABLE READ} transaction</b>: the D14
 * timeouts are its first statement, which also fixes PostgreSQL's snapshot, so every later read in
 * every schema sees the database as of that instant. A money transaction commits its wallet
 * snapshots, ledger posting, business record, limit usage and review row together (MVP-ADR-03), so
 * it is either entirely in the snapshot or entirely outside it: one in flight, or one committing
 * between two reads, cannot produce a false mismatch. The module readers join this transaction
 * ({@code MANDATORY}) and refuse to run outside one. No row lock is taken; the reads never wait on
 * or block a money transaction directly. Each read takes PostgreSQL's {@code ACCESS SHARE} table
 * lock for the rest of the transaction, so DDL works both ways: a migration's lock makes a read
 * wait (bounded by the D14 {@code lock_timeout}), and a migration's {@code ALTER TABLE} waits for a
 * running reconciliation to end, while money traffic on that table queues behind the waiting DDL.
 * Migrations have no lock timeout of their own yet (doc 07 D33, open).
 *
 * <h2>Cost</h2>
 *
 * Each run reads in full: {@code wallet.wallets}, {@code wallet.deposits}, {@code
 * wallet.withdrawals}, the non-zero rows of {@code wallet.daily_limit_usage}, the completed, held
 * and ever-held rows of {@code transfer.transfers} (three scans), {@code risk.risk_reviews}, {@code
 * ledger.ledger_accounts}, {@code ledger.financial_transactions}, and {@code ledger.ledger_entries}
 * twice (grouped by account and by transaction). Grouping is done by the database; the rest is held
 * in memory for the comparison: one small row per wallet, completed movement, ever-held transfer,
 * review and ledger transaction. Each statement is bounded by the D14 {@code statement_timeout};
 * the snapshot is held for the length of the reads only, the comparison runs after it closed. Sized
 * for the MVP, not for paging; see the step 12 report.
 *
 * <h2>Logging</h2>
 *
 * Each mismatch at ERROR, under the fixed alert key {@link #MISMATCH_LOG RECONCILIATION_MISMATCH}
 * with its check and kind and identifiers only; a run summary at INFO with counts and the duration;
 * a run that fails at ERROR under {@link #FAILURE_LOG RECONCILIATION_FAILED} with the exception's
 * full class name and, for a database failure, its SQLState only. A failing run is logged and
 * returns a not-completed report, whatever it threw, except a {@link VirtualMachineError} (such as
 * an {@code OutOfMemoryError}), which is logged and then rethrown, since the JVM may no longer be
 * able to run anything reliably. A run also refuses to start inside a caller's transaction ({@link
 * IllegalTransactionStateException}).
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

  /**
   * The log line of one mismatch, with a fixed key an alert can match: check, kind and identifiers
   * ({@link ReconciliationMismatch#describeIds()}), never amounts or other data.
   */
  static final String MISMATCH_LOG = "RECONCILIATION_MISMATCH: check={}, kind={}, {}";

  /**
   * The log line of a run that could not complete: the exception's fully qualified class name and,
   * when its cause chain holds an {@link SQLException}, the first one's SQLState ({@code none}
   * otherwise, or when the driver gave none). Never the exception's message or any SQL text, which
   * can contain statement text or values.
   */
  static final String FAILURE_LOG =
      "RECONCILIATION_FAILED: reconciliation run did not complete: failure={}, sqlState={}";

  /** A bound on the cause chain walked for an {@link SQLException}, against cyclic chains. */
  private static final int MAX_CAUSES = 32;

  private final WalletReconciliationReads wallets;
  private final TransferReconciliationReads transfers;
  private final RiskReconciliationReads reviews;
  private final LedgerReconciliationReads ledger;
  private final TransactionTimeouts timeouts;
  private final TransactionTemplate snapshot;

  ReconciliationService(
      WalletReconciliationReads wallets,
      TransferReconciliationReads transfers,
      RiskReconciliationReads reviews,
      LedgerReconciliationReads ledger,
      TransactionTimeouts timeouts,
      PlatformTransactionManager transactionManager) {
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    this.snapshot = new TransactionTemplate(transactionManager);
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /**
   * One run, as described on the class. Opens its own transaction and refuses to run inside a
   * caller's: joined to an outer transaction, it would read that transaction's snapshot and
   * isolation, not its own.
   *
   * @return what the run found; {@link ReconciliationReport#completed()} is {@code false} after a
   *     failure, which has been logged
   * @throws IllegalTransactionStateException inside a transaction (a programming error); nothing is
   *     read
   * @throws VirtualMachineError if the run hit one, after logging it; any other failure, {@code
   *     Error} or not, is logged and returned as a not-completed report
   */
  public ReconciliationReport reconcile() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "the reconciliation runs in a transaction of its own");
    }
    long started = System.nanoTime();
    try {
      Reconciler.Snapshot read = Objects.requireNonNull(snapshot.execute(status -> read()));
      List<ReconciliationMismatch> mismatches = Reconciler.compare(read);
      for (ReconciliationMismatch mismatch : mismatches) {
        log.error(MISMATCH_LOG, mismatch.check(), mismatch.kind(), mismatch.describeIds());
      }
      ReconciliationReport report = new ReconciliationReport(true, mismatches, read.rowsRead());
      log.info(
          "Reconciliation run: mismatches={}, byCheck={}, rows={}, durationMs={}",
          mismatches.size(),
          report.countsByCheck(),
          read.rowsRead(),
          (System.nanoTime() - started) / 1_000_000);
      return report;
    } catch (Throwable e) {
      log.error(FAILURE_LOG, e.getClass().getName(), sqlState(e));
      if (e instanceof VirtualMachineError fatal) {
        throw fatal;
      }
      return ReconciliationReport.failed();
    }
  }

  /**
   * The SQLState of the first {@link SQLException} in {@code failure}'s cause chain (itself
   * included), or {@code none}. Only the five-character code PostgreSQL defines, never a message.
   */
  static String sqlState(Throwable failure) {
    Throwable current = failure;
    for (int i = 0; current != null && i < MAX_CAUSES; i++) {
      if (current instanceof SQLException sql) {
        String state = sql.getSQLState();
        return state == null || state.isBlank() ? "none" : state;
      }
      if (current.getCause() == current) {
        break;
      }
      current = current.getCause();
    }
    return "none";
  }

  /** Every read, in the snapshot transaction; the timeouts are its first statement. */
  private Reconciler.Snapshot read() {
    timeouts.apply();
    return new Reconciler.Snapshot(
        wallets.balances(),
        wallets.completedDeposits(),
        wallets.completedWithdrawals(),
        wallets.reservedUsage(),
        transfers.completed(),
        transfers.heldSums(),
        transfers.everHeld(),
        reviews.reviewOutcomes(),
        ledger.accountTotals(),
        ledger.postings());
  }
}
