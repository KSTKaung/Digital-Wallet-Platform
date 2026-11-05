package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.wallet.application.WalletLookupService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Money is conserved when deposits, withdrawals, a refused withdrawal, a concurrent duplicate and
 * recipient lookups hit two wallets at once (doc 01 §6; doc 06 MVP-ADR-03, -08; doc 07 §8; doc 08
 * §4B.4 AC 7, 8, 10). Lookups read wallets without locking them and take their own advisory locks,
 * so they neither block nor deadlock with the money transactions, and they move nothing.
 */
class MixedTrafficConservationIT extends MoneyFlowSupport {

  private static final long TIMEOUT_SECONDS = 60;

  @Autowired WalletLookupService lookups;

  private static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  private static List<String> race(List<Callable<?>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CyclicBarrier start = new CyclicBarrier(tasks.size());
      List<Future<String>> futures = new ArrayList<>();
      for (Callable<?> task : tasks) {
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
    } finally {
      pool.shutdownNow();
    }
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

  /** Σ DEBIT − Σ CREDIT over every ledger entry in the database. */
  private BigDecimal ledgerImbalance() {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)"
            + " FROM ledger.ledger_entries",
        BigDecimal.class);
  }

  private Callable<?> depositTask(Account account, String amount, UUID key) {
    return () -> movements.deposit(account.id(), key, new BigDecimal(amount), "THB", null, null);
  }

  private Callable<?> withdrawalTask(Account account, String amount, UUID key) {
    return () -> movements.withdraw(account.id(), key, new BigDecimal(amount), "THB", null);
  }

  @Test
  void depositsWithdrawalsAndLookupsTogetherConserveMoney() throws Exception {
    Account alice = withWallet();
    Account bob = withWallet();
    UUID aliceWallet = walletId(alice);
    UUID bobWallet = walletId(bob);
    String aliceNumber = (String) walletOf(alice.id()).get("wallet_number");
    String bobNumber = (String) walletOf(bob.id()).get("wallet_number");
    funded(alice, "2000.00");
    funded(bob, "2000.00");
    BigDecimal clearingBefore = cashClearingBalance();
    String aliceIp = randomIp();
    String bobIp = randomIp();

    List<Callable<?>> tasks = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      tasks.add(depositTask(alice, "300.00", UUID.randomUUID()));
      tasks.add(withdrawalTask(alice, "200.00", UUID.randomUUID()));
      tasks.add(depositTask(bob, "500.00", UUID.randomUUID()));
      tasks.add(withdrawalTask(bob, "400.00", UUID.randomUUID()));
    }
    for (int i = 0; i < 4; i++) {
      tasks.add(() -> lookups.lookup(alice.id(), bobNumber, aliceIp));
      tasks.add(() -> lookups.lookup(bob.id(), aliceNumber, bobIp));
    }
    // Never enough money, whatever the order: refused, nothing moves.
    tasks.add(withdrawalTask(alice, "5000.00", UUID.randomUUID()));
    // One request sent twice at once: posted once, the other gets the same answer.
    UUID duplicate = UUID.randomUUID();
    tasks.add(depositTask(bob, "100.00", duplicate));
    tasks.add(depositTask(bob, "100.00", duplicate));

    List<String> outcomes = race(tasks);

    assertThat(outcomes).filteredOn("INSUFFICIENT_BALANCE"::equals).hasSize(1);
    assertThat(outcomes).filteredOn("OK"::equals).hasSize(tasks.size() - 1);
    // Alice: 2000 + 3 x 300 - 3 x 200; Bob: 2000 + 3 x 500 - 3 x 400 + 100 once.
    assertThat(available(aliceWallet)).isEqualByComparingTo("2300.00");
    assertThat(available(bobWallet)).isEqualByComparingTo("2400.00");
    assertReconciled(aliceWallet);
    assertReconciled(bobWallet);
    assertThat(rows("wallet.deposits", bobWallet)).isEqualTo(5);
    // What the wallets gained, the clearing account lost: nothing created or destroyed.
    BigDecimal walletsGained = new BigDecimal("700.00");
    assertThat(cashClearingBalance().subtract(clearingBefore))
        .isEqualByComparingTo(walletsGained.negate());
    assertThat(ledgerImbalance()).isEqualByComparingTo("0");
    // Each lookup counted once.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM wallet.lookup_attempts WHERE user_id IN (?, ?)",
                Integer.class,
                alice.id(),
                bob.id()))
        .isEqualTo(8);
  }
}
