package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.admin.application.ReconciliationReport;
import com.portfolio.wallet.admin.application.ReconciliationService;
import com.portfolio.wallet.common.database.DatabaseTimeoutProperties;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.WalletBalances;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reconciliation reads every module in one snapshot (documentations/06 MVP-ADR-03; doc 07 §5
 * recommendation "one read-only REPEATABLE READ transaction"): a money transaction that commits
 * while a run is between two of its reads is either wholly in what the run compares or wholly
 * outside it, so it never shows as a false mismatch.
 *
 * <p>The wallet snapshots are read first; right after they are read, and before the transfer,
 * review and ledger reads, a deposit to the same wallet commits on another thread. Read at their
 * own instants, the wallet (100.00) and its ledger account (200.00 after the deposit) would
 * disagree and the deposit's record and posting would be read half; in the run's snapshot neither
 * the new balance, the deposit row nor its posting exists. Checked on 2026-11-14 by running the
 * reconciliation in {@code READ COMMITTED}: this test then fails with {@code
 * WALLET_BALANCE_DIFFERS} for the wallet.
 */
class ReconciliationSnapshotIT extends TransferFlowSupport {

  @Autowired ReconciliationService reconciliation;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DatabaseTimeoutProperties databaseTimeouts;
  @MockitoSpyBean WalletReconciliationReads walletReadsSpy;

  @Test
  void aDepositCommittingDuringARunIsNotAFalseMismatch() throws Exception {
    Account customer = funded("100.00");
    UUID wallet = walletId(customer);
    AtomicReference<BigDecimal> seen = new AtomicReference<>();
    AtomicReference<BigDecimal> ledgerOutsideTheSnapshot = new AtomicReference<>();
    // The spy sits behind the reader's MANDATORY transaction proxy, so stubbing needs one too.
    TransactionTemplate stubbing = new TransactionTemplate(transactionManager);
    stubbing.setReadOnly(true);
    stubbing.executeWithoutResult(
        status -> stubDepositAfterBalances(customer, wallet, seen, ledgerOutsideTheSnapshot));

    ReconciliationReport during = reconciliation.reconcile();

    assertThat(during.completed()).isTrue();
    // The deposit committed between the run's first read and its ledger read …
    assertThat(seen.get()).isEqualByComparingTo("100.00");
    assertThat(ledgerOutsideTheSnapshot.get()).isEqualByComparingTo("200.00");
    assertThat(available(customer)).isEqualByComparingTo("200.00");
    // … and the run saw none of it: nothing for the wallet, its account, the deposits or postings.
    assertThat(during.involving(wallet)).isEmpty();
    for (UUID id : depositsAndPostings(wallet)) {
      assertThat(during.involving(id)).as(id.toString()).isEmpty();
    }

    org.mockito.Mockito.reset(walletReadsSpy);
    ReconciliationReport after = reconciliation.reconcile();

    assertThat(after.completed()).isTrue();
    assertThat(after.involving(wallet)).isEmpty();
    for (UUID id : depositsAndPostings(wallet)) {
      assertThat(after.involving(id)).as(id.toString()).isEmpty();
    }
  }

  /**
   * The database itself, asked from inside a run on the run's connection: the transaction is read
   * only, {@code REPEATABLE READ}, and bounded by the D14 statement and lock timeouts.
   */
  @Test
  void theRunIsAReadOnlyRepeatableReadTransactionWithTheD14Timeouts() {
    Map<String, String> settings = new LinkedHashMap<>();
    TransactionTemplate stubbing = new TransactionTemplate(transactionManager);
    stubbing.setReadOnly(true);
    stubbing.executeWithoutResult(
        status ->
            doAnswer(
                    invocation -> {
                      for (String name :
                          List.of(
                              "transaction_read_only",
                              "transaction_isolation",
                              "statement_timeout",
                              "lock_timeout")) {
                        settings.put(name, jdbc.queryForObject("SHOW " + name, String.class));
                        settings.put(
                            name + "_ms",
                            jdbc.queryForObject(
                                "SELECT setting FROM pg_settings WHERE name = ?",
                                String.class,
                                name));
                      }
                      return invocation.callRealMethod();
                    })
                .when(walletReadsSpy)
                .balances());

    assertThat(reconciliation.reconcile().completed()).isTrue();

    assertThat(settings)
        .containsEntry("transaction_read_only", "on")
        .containsEntry("transaction_isolation", "repeatable read")
        .containsEntry(
            "statement_timeout_ms", String.valueOf(databaseTimeouts.statementTimeout().toMillis()))
        .containsEntry(
            "lock_timeout_ms", String.valueOf(databaseTimeouts.lockTimeout().toMillis()));
    // The D14 value as PostgreSQL shows it (10s in application.yaml), not 0 (no timeout).
    assertThat(settings.get("statement_timeout")).isNotEqualTo("0");
  }

  /**
   * The wallet read returns the real snapshots, then a deposit to {@code customer} commits on
   * another thread, and the ledger balance is read outside the run's snapshot.
   */
  private void stubDepositAfterBalances(
      Account customer,
      UUID wallet,
      AtomicReference<BigDecimal> seen,
      AtomicReference<BigDecimal> ledgerOutsideTheSnapshot) {
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<WalletBalances> balances = (List<WalletBalances>) invocation.callRealMethod();
              balances.stream()
                  .filter(b -> b.walletId().equals(wallet))
                  .forEach(b -> seen.set(b.available()));
              // Another thread, so the deposit runs in its own transaction, not in the run's.
              CompletableFuture.runAsync(
                      () -> {
                        try {
                          fund(customer, "100.00");
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      })
                  .get(30, TimeUnit.SECONDS);
              ledgerOutsideTheSnapshot.set(
                  CompletableFuture.supplyAsync(() -> ledgerBalance(customer))
                      .get(30, TimeUnit.SECONDS));
              return balances;
            })
        .when(walletReadsSpy)
        .balances();
  }

  private List<UUID> depositsAndPostings(UUID wallet) {
    List<UUID> ids =
        new java.util.ArrayList<>(
            jdbc.queryForList(
                "SELECT id FROM wallet.deposits WHERE wallet_id = ?", UUID.class, wallet));
    ids.addAll(
        jdbc.queryForList(
            "SELECT t.id FROM ledger.financial_transactions t"
                + " JOIN wallet.deposits d ON d.reference = t.transaction_reference"
                + " WHERE d.wallet_id = ?",
            UUID.class,
            wallet));
    ids.addAll(
        jdbc.queryForList(
            "SELECT id FROM ledger.ledger_accounts WHERE owner_reference = ?", UUID.class, wallet));
    assertThat(ids).hasSize(5);
    return ids;
  }
}
