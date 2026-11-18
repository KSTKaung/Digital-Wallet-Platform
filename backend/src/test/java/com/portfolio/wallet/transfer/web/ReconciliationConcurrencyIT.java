package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.admin.application.ReconciliationReport;
import com.portfolio.wallet.admin.application.ReconciliationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Reconciliation with several instances and with money in flight (documentations/06 MVP-ADR-03
 * "reconciliation as built": "the job takes no locks, writes nothing and repairs nothing";
 * MVP-ADR-24 "Jobs (Q11)": safe with several instances; doc 08 §4C.4 criteria 12, 18; PR 5b step
 * 13).
 *
 * <p>A staff approval is held inside its money transaction after it locked the transfer row and
 * both wallets, captured the hold and wrote its ledger transaction row, all uncommitted. Meanwhile
 * two reconciliation runs start together, as two instances would: both complete without waiting on
 * any of those locks, both report exactly the same mismatches (the shared database's committed
 * state, other tests' rows included), and neither reports anything for this test's rows, which they
 * see still held. The approval then commits unharmed, and a later run sees the completed transfer
 * and reports nothing for it either.
 *
 * <p>Deterministic: a latch holds the approval at a fixed point; every wait is bounded. The
 * scheduled jobs are off in the tests, so nothing else writes while the gate is closed.
 */
class ReconciliationConcurrencyIT extends TransferReviewFlowSupport {

  private static final long WAIT_SECONDS = 60;

  @Autowired ReconciliationService reconciliation;

  private List<Object> ownIds(UUID transferId, Account... customers) {
    List<Object> ids = new ArrayList<>();
    ids.add(transferId);
    for (Account customer : customers) {
      UUID wallet = walletId(customer);
      ids.add(wallet);
      ids.addAll(
          jdbc.queryForList(
              "SELECT id FROM ledger.ledger_accounts WHERE owner_reference = ?",
              UUID.class,
              wallet));
      ids.addAll(
          jdbc.queryForList(
              "SELECT DISTINCT e.transaction_id FROM ledger.ledger_entries e"
                  + " JOIN ledger.ledger_accounts a ON a.id = e.account_id"
                  + " WHERE a.owner_reference = ?",
              UUID.class,
              wallet));
      ids.addAll(
          jdbc.queryForList(
              "SELECT id FROM wallet.deposits WHERE wallet_id = ?", UUID.class, wallet));
    }
    ids.addAll(
        jdbc.queryForList(
            "SELECT id FROM risk.risk_reviews WHERE transfer_id = ?", UUID.class, transferId));
    return ids;
  }

  private static void assertNothingFor(ReconciliationReport report, List<Object> ids) {
    assertThat(report.completed()).isTrue();
    for (Object id : ids) {
      assertThat(report.involving(id)).as(String.valueOf(id)).isEmpty();
    }
  }

  @Test
  void twoRunsAtOnceDuringAnUncommittedApprovalNeitherWaitNorDisagree() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    String reference = (String) transferRow(id).get("reference");
    CountDownLatch posted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // The approval's ledger transaction row is written: the transfer row and both wallets are
    // locked and the hold captured, all uncommitted.
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              posted.countDown();
              assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(ledgerRowsSpy)
        .insertTransaction(any(), eq(reference), any(), any(), any());

    ExecutorService pool = Executors.newFixedThreadPool(3);
    MockHttpServletResponse approval;
    List<ReconciliationReport> during = new ArrayList<>();
    try {
      Future<MockHttpServletResponse> approving = pool.submit(() -> approveReview(id, reviewer));
      assertThat(posted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
                      + " WHERE c.relname = 'wallets' AND l.granted"
                      + " AND l.mode IN ('RowShareLock', 'RowExclusiveLock')",
                  Integer.class))
          .as("the approval holds its wallet row locks")
          .isPositive();

      CyclicBarrier together = new CyclicBarrier(2);
      List<Future<ReconciliationReport>> runs = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        runs.add(
            pool.submit(
                () -> {
                  together.await(WAIT_SECONDS, TimeUnit.SECONDS);
                  return reconciliation.reconcile();
                }));
      }
      // Both finish while the approval still holds its locks: they waited on none of them.
      for (Future<ReconciliationReport> run : runs) {
        during.add(run.get(WAIT_SECONDS, TimeUnit.SECONDS));
      }
      assertThat(posted.getCount()).isZero();
      assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");

      release.countDown();
      approval = approving.get(WAIT_SECONDS, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
      Mockito.reset(ledgerRowsSpy);
    }

    // Two instances, one committed state: the same mismatches, and none for this test's rows.
    assertThat(during.get(0).mismatches())
        .containsExactlyInAnyOrderElementsOf(during.get(1).mismatches());
    assertThat(during.get(0).rowsRead()).isEqualTo(during.get(1).rowsRead());
    List<Object> own = ownIds(id, sender, recipient);
    for (ReconciliationReport report : during) {
      assertNothingFor(report, own);
    }

    // The approval committed unharmed: captured once, posted once.
    assertThat(approval.getStatus()).as(approval.getContentAsString()).isEqualTo(200);
    assertThat(transferRow(id)).containsEntry("status", "COMPLETED");
    assertBalances(sender, "700.00", "0.00");
    assertBalances(recipient, "1300.00", "0.00");
    assertThat(ledgerTransactions(reference)).isOne();
    assertBalancedPosting(reference, HOLD);
    assertThat(reviewRows(id)).hasSize(1);

    // A later run sees the completion, and it is consistent too.
    assertNothingFor(reconciliation.reconcile(), ownIds(id, sender, recipient));
  }
}
