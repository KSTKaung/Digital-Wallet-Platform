package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Freeze against a ledger posting on the same wallet (doc 06 MVP-ADR-08, -10 amendment; doc 07 §8;
 * doc 08 §4B.2 Q9). Deposits do not exist before PR 4b, so the posting is a fixture that follows
 * the documented order of a money flow: the wallet row {@code FOR UPDATE}, then balanced entries
 * (V2's entry trigger locks the customer account {@code FOR SHARE}), then the snapshot with the
 * version check. Each test makes one side wait on the other, observed in {@code pg_stat_activity},
 * and checks that they serialise, that nothing deadlocks, and that the wallet and its ledger
 * account always end in the same status.
 */
class WalletFreezeConcurrencyIT extends DatabaseIntegrationTest {

  private static final long TIMEOUT_SECONDS = 30;

  @Autowired WalletStatusService statuses;
  @Autowired PlatformTransactionManager transactions;

  private ExecutorService pool;
  private UUID customer;
  private UUID walletId;
  private UUID accountId;

  @BeforeEach
  void setUp() throws SQLException {
    pool = Executors.newFixedThreadPool(4);
    // A real number: the domain checks the Luhn digit that random fixture digits lack.
    Map<String, Object> wallet =
        with(validWallet(), "wallet_number", new WalletNumberGenerator().next().value());
    insert("wallet.wallets", wallet);
    walletId = (UUID) wallet.get("id");
    customer = (UUID) wallet.get("customer_id");
    accountId = UUID.randomUUID();
    insert(
        "ledger.ledger_accounts",
        row(
            "id",
            accountId,
            "account_type",
            "CUSTOMER",
            "owner_reference",
            walletId,
            "currency",
            THB));
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  // Fixtures ------------------------------------------------------------------------------------

  /**
   * The posting, up to the commit: wallet lock, status re-check, balanced entries, snapshot. A real
   * money flow refuses a non-ACTIVE wallet after its lock (doc 07 §8 recommendation); the fixture
   * records the status it saw and posts anyway, so the ledger's own guard is exercised too.
   */
  private String postDepositUncommitted(Connection c, String amount) throws SQLException {
    String status =
        queryOne(
            c, "SELECT status FROM wallet.wallets WHERE id = ? FOR UPDATE", String.class, walletId);
    UUID transaction = insertFinancialTransaction(c, "DEPOSIT");
    insertEntry(c, transaction, cashClearingAccountId(), "DEBIT", amount);
    insertEntry(c, transaction, accountId, "CREDIT", amount);
    int updated =
        update(
            c,
            "UPDATE wallet.wallets SET available_balance = available_balance + ?,"
                + " version = version + 1 WHERE id = ? AND version = ?",
            new BigDecimal(amount),
            walletId,
            queryOne(c, "SELECT version FROM wallet.wallets WHERE id = ?", Long.class, walletId));
    assertThat(updated).isEqualTo(1);
    return status;
  }

  private BigDecimal balance() {
    return jdbc.queryForObject(
        "SELECT available_balance FROM wallet.wallets WHERE id = ?", BigDecimal.class, walletId);
  }

  private int entries() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ledger.ledger_entries WHERE account_id = ?",
        Integer.class,
        accountId);
  }

  private String walletStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM wallet.wallets WHERE id = ?", String.class, walletId);
  }

  private String ledgerStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM ledger.ledger_accounts WHERE id = ?", String.class, accountId);
  }

  private void assertStatuses(String status) {
    assertThat(walletStatus()).isEqualTo(status);
    assertThat(ledgerStatus()).isEqualTo(status);
  }

  /** The backend running {@link #freezeHeldOpen}'s transaction. */
  private final AtomicInteger freezePid = new AtomicInteger();

  private void awaitLockWaiters(Connection holder, int count) throws SQLException {
    awaitBlockedBy(backendPid(holder), count);
  }

  /** Runs the customer's freeze in a transaction that waits for {@code release} to commit. */
  private Future<WalletStatusView> freezeHeldOpen(CountDownLatch done, CountDownLatch release) {
    freezePid.set(0);
    TransactionTemplate template = new TransactionTemplate(transactions);
    return pool.submit(
        () ->
            template.execute(
                status -> {
                  // The freeze's own backend, which the queued statement must wait on (J9).
                  freezePid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                  WalletStatusView view = statuses.freezeOwn(customer, null);
                  done.countDown();
                  try {
                    assertThat(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                  }
                  return view;
                }));
  }

  // Tests ---------------------------------------------------------------------------------------

  /** A posting holds the wallet; the freeze waits for it, then freezes both. */
  @Test
  void aFreezeWaitsForAPostingInFlightAndThenFreezesBoth() throws Exception {
    Future<WalletStatusView> freeze;
    try (Connection posting = openTransaction()) {
      assertThat(postDepositUncommitted(posting, "100.00")).isEqualTo("ACTIVE");

      freeze = pool.submit(() -> statuses.freezeOwn(customer, null));
      awaitLockWaiters(posting, 1);
      assertThat(freeze.isDone()).as("the freeze waits on the wallet row").isFalse();
      posting.commit();
    }

    assertThat(freeze.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo("FROZEN");
    assertStatuses("FROZEN");
    assertThat(balance()).isEqualByComparingTo("100.00");
    assertThat(entries()).isEqualTo(1);
  }

  /**
   * The freeze holds the wallet and the account; a posting queued behind it on the wallet finds the
   * wallet frozen and its account refuses the entry, so nothing is posted.
   */
  @Test
  void aPostingQueuedBehindAFreezeFindsItFrozenAndPostsNothing() throws Exception {
    CountDownLatch frozen = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<WalletStatusView> freeze = freezeHeldOpen(frozen, release);
    assertThat(frozen.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    Future<String> posting =
        pool.submit(
            () -> {
              try (Connection c = openTransaction()) {
                String seen = postDepositUncommitted(c, "100.00");
                c.commit();
                return seen;
              }
            });
    awaitBlockedBy(freezePid.get(), 1);
    assertThat(posting.isDone()).as("the posting waits on the wallet row").isFalse();
    release.countDown();

    assertThat(freeze.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo("FROZEN");
    assertThatThrownBy(() -> posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasMessageContaining("accepts no new entries")
        .hasMessageNotContaining("deadlock");
    assertStatuses("FROZEN");
    assertThat(balance()).isEqualByComparingTo("0.00");
    assertThat(entries()).isZero();
  }

  /**
   * Even a path that skipped the wallet lock cannot post to a frozen wallet: its entry waits on the
   * account the freeze holds, then is refused.
   */
  @Test
  void anEntryWithoutTheWalletLockWaitsForTheFreezeAndIsRefused() throws Exception {
    CountDownLatch frozen = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<WalletStatusView> freeze = freezeHeldOpen(frozen, release);
    assertThat(frozen.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    Future<Void> entry =
        pool.submit(
            () -> {
              try (Connection c = openTransaction()) {
                UUID transaction = insertFinancialTransaction(c, "DEPOSIT");
                insertEntry(c, transaction, cashClearingAccountId(), "DEBIT", "5.00");
                insertEntry(c, transaction, accountId, "CREDIT", "5.00");
                c.commit();
              }
              return null;
            });
    awaitBlockedBy(freezePid.get(), 1);
    release.countDown();

    freeze.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThatThrownBy(() -> entry.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasMessageContaining("is FROZEN; it accepts no new entries");
    assertThat(entries()).isZero();
  }

  /**
   * Many concurrent freezes and unfreezes: each serialises on the wallet row, none deadlocks, the
   * two statuses always agree, and every real change, and only a real change, is audited and moves
   * the version.
   */
  @Test
  void concurrentFreezesAndUnfreezesKeepBothStatusesInStepAndAuditEachRealChange()
      throws Exception {
    UUID admin = UUID.randomUUID();
    List<Callable<WalletStatusView>> calls = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      calls.add(
          i % 3 == 0
              ? () -> statuses.freezeOwn(customer, null)
              : i % 3 == 1
                  ? () -> statuses.freezeByStaff(admin, customer, "check", null)
                  : () -> statuses.unfreezeByStaff(admin, customer, "cleared", null));
    }

    for (Future<WalletStatusView> result : pool.invokeAll(calls)) {
      assertThat(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status()).isIn("ACTIVE", "FROZEN");
    }

    assertThat(ledgerStatus()).isEqualTo(walletStatus());
    long version =
        jdbc.queryForObject(
            "SELECT version FROM wallet.wallets WHERE id = ?", Long.class, walletId);
    int changes =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit.audit_logs WHERE resource = 'WALLET' AND resource_id = ?"
                + " AND action IN ('WALLET_FREEZE', 'WALLET_UNFREEZE')",
            Integer.class,
            walletId.toString());
    assertThat(version).isEqualTo(changes);
    assertThat(changes).isPositive();
  }
}
