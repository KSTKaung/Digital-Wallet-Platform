package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.wallet.application.MoneyMovementView;
import com.portfolio.wallet.wallet.application.WalletStatusService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Money movements under concurrency (documentations/06 MVP-ADR-06, -08, -18; doc 07 §8; doc 08
 * §4B.4 AC 7, 8, 9). The service is called from many threads at once, each on its own connection;
 * wallets are created through the real approval flow.
 */
class MoneyConcurrencyIT extends MoneyFlowSupport {

  private static final long TIMEOUT_SECONDS = 60;
  private static final int THREADS = 10;

  @Autowired WalletStatusService statuses;

  private ExecutorService pool;

  @BeforeEach
  void startPool() {
    pool = Executors.newFixedThreadPool(THREADS);
  }

  @AfterEach
  void stopPool() {
    pool.shutdownNow();
  }

  /** Runs every task from one barrier and returns each outcome: an error code or "OK". */
  private List<String> race(List<Callable<MoneyMovementView>> tasks) throws Exception {
    CyclicBarrier start = new CyclicBarrier(tasks.size());
    List<Future<String>> futures = new ArrayList<>();
    for (Callable<MoneyMovementView> task : tasks) {
      futures.add(
          pool.submit(
              () -> {
                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try {
                  task.call();
                  return "OK";
                } catch (ApiException e) {
                  return e.errorCode().name();
                }
              }));
    }
    List<String> outcomes = new ArrayList<>();
    for (Future<String> future : futures) {
      outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
    return outcomes;
  }

  private Callable<MoneyMovementView> withdrawal(Account account, String amount, UUID key) {
    return () -> movements.withdraw(account.id(), key, new BigDecimal(amount), "THB", null);
  }

  @Test
  void concurrentWithdrawalsNeverOverdraw() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "35000.00");

    List<Callable<MoneyMovementView>> tasks = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      tasks.add(withdrawal(customer, "10000.00", UUID.randomUUID()));
    }
    List<String> outcomes = race(tasks);

    assertThat(outcomes).filteredOn("OK"::equals).hasSize(3);
    assertThat(outcomes).filteredOn("INSUFFICIENT_BALANCE"::equals).hasSize(7);
    assertThat(available(wallet)).isEqualByComparingTo("5000.00");
    assertThat(ledgerTransactions(wallet)).isEqualTo(4);
    assertReconciled(wallet);
  }

  @Test
  void concurrentWithdrawalsNeverExceedTheDailyLimit() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "100000.00");

    List<Callable<MoneyMovementView>> tasks = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      tasks.add(withdrawal(customer, "10000.00", UUID.randomUUID()));
    }
    List<String> outcomes = race(tasks);

    // Daily withdrawal limit 50,000.00 (MVP-ADR-18).
    assertThat(outcomes).filteredOn("OK"::equals).hasSize(5);
    assertThat(outcomes).filteredOn("DAILY_LIMIT_EXCEEDED"::equals).hasSize(5);
    assertThat(usedToday(wallet, "WITHDRAWAL")).isEqualByComparingTo("50000.00");
    assertThat(available(wallet)).isEqualByComparingTo("50000.00");
    assertReconciled(wallet);
  }

  @Test
  void concurrentDuplicatesWithOneKeyPostOnce() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();

    List<Future<MoneyMovementView>> results = new ArrayList<>();
    CyclicBarrier start = new CyclicBarrier(THREADS);
    for (int i = 0; i < THREADS; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return movements.deposit(
                    customer.id(), key, new BigDecimal("1000.00"), "THB", "rent", null);
              }));
    }
    Set<UUID> ids = new HashSet<>();
    for (Future<MoneyMovementView> result : results) {
      ids.add(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).id());
    }

    assertThat(ids).hasSize(1);
    assertThat(available(wallet)).isEqualByComparingTo("1000.00");
    assertThat(ledgerTransactions(wallet)).isEqualTo(1);
    assertThat(rows("wallet.deposits", wallet)).isEqualTo(1);
    assertThat(moneyAuditRows(customer.id())).isEqualTo(1);
  }

  /** Two-way traffic on one wallet: money is conserved and every posting balanced. */
  @Test
  void mixedConcurrentDepositsAndWithdrawalsConserveMoney() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "5000.00");
    BigDecimal clearingBefore = cashClearingBalance();

    List<Callable<MoneyMovementView>> tasks = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      UUID key = UUID.randomUUID();
      tasks.add(
          i % 2 == 0
              ? () ->
                  movements.deposit(customer.id(), key, new BigDecimal("300.00"), "THB", null, null)
              : withdrawal(customer, "700.00", key));
    }
    assertThat(race(tasks)).containsOnly("OK");

    // 5 x 300 in, 5 x 700 out.
    assertThat(available(wallet)).isEqualByComparingTo("3000.00");
    assertReconciled(wallet);
    // What left the wallet arrived on SYS-CASH-CLEARING and vice versa; other tests may post to it
    // concurrently only within their own runs, which are sequential.
    assertThat(cashClearingBalance().subtract(clearingBefore)).isEqualByComparingTo("2000.00");
  }

  private BigDecimal cashClearingBalance() {
    return jdbc.queryForObject(
        """
        SELECT coalesce(sum(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
        FROM ledger.ledger_entries e JOIN ledger.ledger_accounts a ON a.id = e.account_id
        WHERE a.code = 'SYS-CASH-CLEARING'
        """,
        BigDecimal.class);
  }

  // Freeze racing a withdrawal (AC 9) -------------------------------------------------------------

  /**
   * The wallet row is held by a test transaction; a withdrawal and then a freeze queue behind it.
   * Released, the withdrawal goes first and completes, then the freeze: the wallet ends {@code
   * FROZEN} with its ledger account, and the money moved before the freeze.
   */
  @Test
  void aWithdrawalQueuedBeforeAFreezeCompletesFirst() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000.00");

    Future<MoneyMovementView> withdrawal;
    Future<?> freeze;
    try (Connection holder = openTransaction()) {
      queryOne(holder, "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE", UUID.class, wallet);
      int pid = backendPid(holder);
      withdrawal = pool.submit(withdrawal(customer, "400.00", UUID.randomUUID()));
      awaitBlockedBy(pid, 1);
      freeze = pool.submit(() -> statuses.freezeOwn(customer.id(), null));
      awaitBlockedBy(pid, 2);
      holder.rollback();
    }

    assertThat(withdrawal.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
    freeze.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(available(wallet)).isEqualByComparingTo("600.00");
    assertThat(walletOf(customer.id()).get("status")).isEqualTo("FROZEN");
    assertThat(ledgerStatusOf(wallet)).isEqualTo("FROZEN");
    assertReconciled(wallet);
  }

  /** The reverse order: the freeze goes first, so the withdrawal finds the wallet frozen. */
  @Test
  void aWithdrawalQueuedAfterAFreezeIsRefused() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000.00");

    Future<?> freeze;
    Future<String> withdrawal;
    try (Connection holder = openTransaction()) {
      queryOne(holder, "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE", UUID.class, wallet);
      int pid = backendPid(holder);
      freeze = pool.submit(() -> statuses.freezeOwn(customer.id(), null));
      awaitBlockedBy(pid, 1);
      withdrawal =
          pool.submit(
              () -> {
                try {
                  withdrawal(customer, "400.00", UUID.randomUUID()).call();
                  return "OK";
                } catch (ApiException e) {
                  return e.errorCode().name();
                }
              });
      awaitBlockedBy(pid, 2);
      holder.rollback();
    }

    freeze.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(withdrawal.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("WALLET_FROZEN");
    assertThat(available(wallet)).isEqualByComparingTo("1000.00");
    assertThat(ledgerTransactions(wallet)).isEqualTo(1);
    assertReconciled(wallet);
  }
}
