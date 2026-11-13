package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.wallet.application.ApprovalOutcome;
import com.portfolio.wallet.wallet.application.LockedTransferWallets;
import com.portfolio.wallet.wallet.application.TransferPrecheck;
import com.portfolio.wallet.wallet.application.TransferSettlement;
import com.portfolio.wallet.wallet.application.TransferWalletView;
import com.portfolio.wallet.wallet.application.UnavailableWallet;
import com.portfolio.wallet.wallet.application.WalletTransferService;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The wallet side of transfers against PostgreSQL (documentations/06 MVP-ADR-03, -08, -18, -22,
 * -24; doc 07 §8; doc 08 §4C.4 criteria 2, 5, 6, 10, 11): money moves exactly once with a balanced
 * {@code TRANSFER} ledger transaction, a hold posts nothing and leaves the recipient alone, release
 * and capture settle on the stored day even across a Bangkok midnight, and available + reserved
 * equals the ledger balance for both wallets after every operation (MVP-ADR-03 check 1).
 *
 * <p>Wallets come from the real KYC flow and are funded by real deposits. Each money transaction
 * here is what PR 5a step 5 will run inside its idempotency engine: the D14 timeouts first, then
 * the two wallets locked in one statement, then one operation.
 */
class WalletTransferServiceIT extends MoneyFlowSupport {

  // 16:59:59.5Z is still 2026-11-09 in Bangkok; 17:00:00.5Z is 2026-11-10 (MVP-ADR-22).
  private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-11-09T16:59:59.500Z");
  private static final Instant AFTER_MIDNIGHT = Instant.parse("2026-11-09T17:00:00.500Z");
  private static final LocalDate DAY = LocalDate.parse("2026-11-09");
  private static final LocalDate NEXT_DAY = LocalDate.parse("2026-11-10");
  private static final long TIMEOUT_SECONDS = 60;

  @Autowired WalletTransferService transfers;
  @Autowired TransactionTimeouts timeouts;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired WalletRepository walletRepository;

  private TransactionTemplate transactions;
  private ExecutorService pool;

  @BeforeEach
  void setUpTransactions() {
    transactions = new TransactionTemplate(transactionManager);
    pool = Executors.newFixedThreadPool(8);
  }

  @AfterEach
  void stopPool() {
    pool.shutdownNow();
  }

  /** One money transaction: timeouts, both wallets locked, then {@code operation}. */
  private <T> T inMoneyTransaction(
      UUID sender, UUID recipient, Function<LockedTransferWallets, T> operation) {
    return transactions.execute(
        status -> {
          timeouts.apply();
          return operation.apply(transfers.lock(sender, recipient));
        });
  }

  private static Money m(String amount) {
    return Money.of(amount, Money.THB);
  }

  private static String newReference() {
    return "TRF-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  // Database reads ----------------------------------------------------------------------------

  private BigDecimal reserved(UUID walletId) {
    return jdbc.queryForObject(
        "SELECT reserved_balance FROM wallet.wallets WHERE id = ?", BigDecimal.class, walletId);
  }

  private long version(UUID walletId) {
    return jdbc.queryForObject(
        "SELECT version FROM wallet.wallets WHERE id = ?", Long.class, walletId);
  }

  /** used and reserved of the wallet's TRANSFER usage on {@code date}; empty if no row. */
  private List<Map<String, Object>> transferUsage(UUID walletId, LocalDate date) {
    return jdbc.queryForList(
        "SELECT used, reserved FROM wallet.daily_limit_usage"
            + " WHERE wallet_id = ? AND operation = 'TRANSFER' AND limit_date = ?",
        walletId,
        date);
  }

  private void assertUsage(UUID walletId, LocalDate date, String used, String reserved) {
    List<Map<String, Object>> rows = transferUsage(walletId, date);
    assertThat(rows).hasSize(1);
    assertThat((BigDecimal) rows.get(0).get("used")).isEqualByComparingTo(used);
    assertThat((BigDecimal) rows.get(0).get("reserved")).isEqualByComparingTo(reserved);
  }

  /** MVP-ADR-03 check 1: available + reserved = Σ CREDIT − Σ DEBIT; every posting balanced. */
  private void assertTotalMatchesLedger(UUID walletId) {
    assertThat(available(walletId).add(reserved(walletId)))
        .isEqualByComparingTo(ledgerBalance(walletId));
    assertThat(unbalancedTransactions(walletId)).isZero();
  }

  private void assertBalances(UUID walletId, String available, String reserved) {
    assertThat(available(walletId)).isEqualByComparingTo(available);
    assertThat(reserved(walletId)).isEqualByComparingTo(reserved);
    assertTotalMatchesLedger(walletId);
  }

  private List<Map<String, Object>> ledgerTransaction(String reference) {
    return jdbc.queryForList(
        """
        SELECT t.transaction_type, e.entry_type, e.amount, a.owner_reference
        FROM ledger.financial_transactions t
        JOIN ledger.ledger_entries e ON e.transaction_id = t.id
        JOIN ledger.ledger_accounts a ON a.id = e.account_id
        WHERE t.transaction_reference = ?
        ORDER BY e.entry_type
        """,
        reference);
  }

  private UUID[] twoWallets(String senderFunds) throws Exception {
    Account sender = withWallet();
    Account recipient = withWallet("FULL");
    if (senderFunds != null) {
      funded(sender, senderFunds);
    }
    return new UUID[] {walletId(sender), walletId(recipient)};
  }

  // Complete (criterion 5) ------------------------------------------------------------------------

  @Test
  void aCompletionMovesTheMoneyExactlyOnceWithABalancedTransferPosting() throws Exception {
    UUID[] w = twoWallets("1000.00");
    String reference = newReference();

    TransferSettlement settlement =
        inMoneyTransaction(
            w[0], w[1], locked -> transfers.complete(locked, m("250.50"), reference));

    assertBalances(w[0], "749.50", "0.00");
    assertBalances(w[1], "250.50", "0.00");
    assertUsage(w[0], settlement.limitDate(), "250.50", "0.00");
    assertThat(transferUsage(w[1], settlement.limitDate())).isEmpty();
    List<Map<String, Object>> entries = ledgerTransaction(reference);
    assertThat(entries).hasSize(2);
    assertThat(entries)
        .allSatisfy(e -> assertThat(e.get("transaction_type")).isEqualTo("TRANSFER"));
    assertThat(entries.get(0))
        .containsEntry("entry_type", "CREDIT")
        .containsEntry("owner_reference", w[1]);
    assertThat(entries.get(1))
        .containsEntry("entry_type", "DEBIT")
        .containsEntry("owner_reference", w[0]);
    assertThat(entries)
        .allSatisfy(e -> assertThat((BigDecimal) e.get("amount")).isEqualByComparingTo("250.50"));
    assertThat(
            jdbc.queryForObject(
                "SELECT id FROM ledger.financial_transactions WHERE transaction_reference = ?",
                UUID.class,
                reference))
        .isEqualTo(settlement.ledgerTransactionId());

    // The same reference cannot post twice: the whole second transaction rolls back.
    assertThatThrownBy(
            () ->
                inMoneyTransaction(
                    w[0], w[1], locked -> transfers.complete(locked, m("1.00"), reference)))
        .isInstanceOf(DuplicateKeyException.class);
    assertBalances(w[0], "749.50", "0.00");
    assertBalances(w[1], "250.50", "0.00");
    assertUsage(w[0], settlement.limitDate(), "250.50", "0.00");
  }

  @Test
  void aRefusalUnderTheLocksChangesNothing() throws Exception {
    UUID[] w = twoWallets("100.00");
    long before = version(w[0]);

    for (String[] refusal :
        new String[][] {
          {"100.01", "INSUFFICIENT_BALANCE"},
          {"0.99", "INVALID_AMOUNT"},
          {"50000.01", "SINGLE_LIMIT_EXCEEDED"}
        }) {
      assertThatThrownBy(
              () ->
                  inMoneyTransaction(
                      w[0],
                      w[1],
                      locked -> transfers.complete(locked, m(refusal[0]), newReference())))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.errorCode().name()).isEqualTo(refusal[1]));
    }
    assertBalances(w[0], "100.00", "0.00");
    assertBalances(w[1], "0.00", "0.00");
    assertThat(version(w[0])).isEqualTo(before);
    assertThat(ledgerTransactions(w[1])).isZero();
  }

  // Hold (criterion 6) ----------------------------------------------------------------------------

  @Test
  void aHoldReservesWithoutLedgerEntriesAndLeavesTheRecipientUnchanged() throws Exception {
    UUID[] w = twoWallets("1000.00");
    int senderPostings = ledgerTransactions(w[0]);
    long recipientVersion = version(w[1]);

    LocalDate limitDate =
        inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("300.00")));

    assertBalances(w[0], "700.00", "300.00");
    assertBalances(w[1], "0.00", "0.00");
    assertThat(version(w[1])).isEqualTo(recipientVersion);
    assertThat(ledgerTransactions(w[0])).isEqualTo(senderPostings);
    assertThat(ledgerTransactions(w[1])).isZero();
    assertUsage(w[0], limitDate, "0.00", "300.00");
  }

  /** MVP-ADR-18: the daily limit counts used + reserved under the locks. */
  @Test
  void heldAmountsCountTowardTheDailyLimitUntilReleased() throws Exception {
    // The sender is BASIC: 100,000.00 a day. A deposit is at most 100,000.00.
    UUID[] w = twoWallets(null);
    fundWallet(w[0], "100000.00");
    fundWallet(w[0], "100.00");

    LocalDate day = inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("50000.00")));
    inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("50000.00")));
    assertThatThrownBy(
            () ->
                inMoneyTransaction(
                    w[0], w[1], locked -> transfers.complete(locked, m("1.00"), newReference())))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED));

    inMoneyTransaction(
        w[0],
        w[1],
        locked -> {
          transfers.release(locked, m("50000.00"), day);
          return null;
        });
    inMoneyTransaction(w[0], w[1], locked -> transfers.complete(locked, m("1.00"), newReference()));

    assertUsage(w[0], day, "1.00", "50000.00");
    assertBalances(w[0], "50099.00", "50000.00");
    assertBalances(w[1], "1.00", "0.00");
  }

  // Release and capture on the stored day (criteria 7, 8, 15; MVP-ADR-22) -------------------------

  @Test
  void aReleaseAfterBangkokMidnightRestoresTheHoldOnItsStoredDay() throws Exception {
    UUID[] w = twoWallets("1000.00");
    mutableClock.set(BEFORE_MIDNIGHT);
    LocalDate limitDate =
        inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("300.00")));
    assertThat(limitDate).isEqualTo(DAY);

    mutableClock.set(AFTER_MIDNIGHT);
    inMoneyTransaction(
        w[0],
        w[1],
        locked -> {
          transfers.release(locked, m("300.00"), limitDate);
          return null;
        });

    assertBalances(w[0], "1000.00", "0.00");
    assertBalances(w[1], "0.00", "0.00");
    assertUsage(w[0], DAY, "0.00", "0.00");
    assertThat(transferUsage(w[0], NEXT_DAY)).isEmpty();
  }

  @Test
  void anApprovalAfterBangkokMidnightCapturesTheHoldOnItsStoredDay() throws Exception {
    UUID[] w = twoWallets("1000.00");
    mutableClock.set(BEFORE_MIDNIGHT);
    LocalDate limitDate =
        inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("300.00")));
    int senderPostings = ledgerTransactions(w[0]);

    mutableClock.set(AFTER_MIDNIGHT);
    String reference = newReference();
    ApprovalOutcome outcome =
        inMoneyTransaction(
            w[0], w[1], locked -> transfers.approve(locked, m("300.00"), limitDate, reference));
    assertThat(outcome).isInstanceOf(ApprovalOutcome.Captured.class);
    TransferSettlement settlement = ((ApprovalOutcome.Captured) outcome).settlement();

    assertThat(settlement.limitDate()).isEqualTo(DAY);
    assertBalances(w[0], "700.00", "0.00");
    assertBalances(w[1], "300.00", "0.00");
    assertUsage(w[0], DAY, "300.00", "0.00");
    assertThat(transferUsage(w[0], NEXT_DAY)).isEmpty();
    assertThat(ledgerTransactions(w[0])).isEqualTo(senderPostings + 1);
    assertThat(ledgerTransaction(reference)).hasSize(2);

    // The hold is gone: a second release or capture of it is a programming error.
    assertThatThrownBy(
            () ->
                inMoneyTransaction(
                    w[0],
                    w[1],
                    locked -> {
                      transfers.release(locked, m("300.00"), limitDate);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertBalances(w[0], "700.00", "0.00");
  }

  @Test
  void aReleaseWorksOnAFrozenSenderAndAFrozenRecipient() throws Exception {
    UUID[] w = twoWallets("1000.00");
    LocalDate limitDate =
        inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("10.00")));
    jdbc.update("UPDATE wallet.wallets SET status = 'FROZEN' WHERE id IN (?, ?)", w[0], w[1]);

    TransferWalletView[] seen = new TransferWalletView[2];
    inMoneyTransaction(
        w[0],
        w[1],
        locked -> {
          seen[0] = locked.sender();
          seen[1] = locked.recipient();
          transfers.release(locked, m("10.00"), limitDate);
          return null;
        });

    assertThat(seen[0].active()).isFalse();
    assertThat(seen[1].active()).isFalse();
    assertBalances(w[0], "1000.00", "0.00");
    assertUsage(w[0], limitDate, "0.00", "0.00");
  }

  // Under the locks -------------------------------------------------------------------------------

  /** MVP-ADR-20: a recipient frozen after the pre-check is the identical 404 under the lock. */
  @Test
  void aRecipientFrozenBeforeTheLockIsTheIdenticalNotFound() throws Exception {
    UUID[] w = twoWallets("1000.00");
    jdbc.update("UPDATE wallet.wallets SET status = 'FROZEN' WHERE id = ?", w[1]);

    assertThatThrownBy(
            () ->
                inMoneyTransaction(
                    w[0], w[1], locked -> transfers.complete(locked, m("10.00"), newReference())))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    assertThatThrownBy(
            () -> inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("10.00"))))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    assertBalances(w[0], "1000.00", "0.00");
  }

  /** Doc 07 §8: a version that moved under the row lock fails the whole transaction. */
  @Test
  void anOptimisticVersionConflictRollsEverythingBack() throws Exception {
    UUID[] w = twoWallets("1000.00");
    long senderVersion = version(w[0]);
    int senderPostings = ledgerTransactions(w[0]);

    assertThatThrownBy(
            () ->
                inMoneyTransaction(
                    w[0],
                    w[1],
                    locked -> {
                      // A write that skipped the lock order, on the money transaction's own
                      // connection (the row lock is held, so nobody else could).
                      jdbc.update(
                          "UPDATE wallet.wallets SET version = version + 1 WHERE id = ?", w[1]);
                      return transfers.complete(locked, m("10.00"), newReference());
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("version");

    assertBalances(w[0], "1000.00", "0.00");
    assertBalances(w[1], "0.00", "0.00");
    assertThat(version(w[0])).isEqualTo(senderVersion);
    assertThat(ledgerTransactions(w[0])).isEqualTo(senderPostings);
    // The usage row the rolled-back transaction created on the test clock's day is gone too.
    assertThat(
            transferUsage(
                w[0],
                LocalDate.ofInstant(mutableClock.instant(), java.time.ZoneId.of("Asia/Bangkok"))))
        .isEmpty();
  }

  /**
   * MVP-ADR-08: both wallets are locked in one statement in ascending id order, so N concurrent
   * transfers A → B and N B → A never deadlock. PostgreSQL would abort one of a deadlocked pair
   * after its deadlock_timeout (1 s), which fails this test. Money is conserved.
   */
  @Test
  void oppositeDirectionsConcurrentlyNeverDeadlockAndConserveMoney() throws Exception {
    UUID[] w = twoWallets("1000.00");
    fundWallet(w[1], "1000.00");
    int threadsPerDirection = 4;
    int transfersPerThread = 10;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int t = 0; t < 2 * threadsPerDirection; t++) {
      UUID from = t % 2 == 0 ? w[0] : w[1];
      UUID to = t % 2 == 0 ? w[1] : w[0];
      results.add(
          pool.submit(
              () -> {
                start.await();
                for (int i = 0; i < transfersPerThread; i++) {
                  inMoneyTransaction(
                      from, to, locked -> transfers.complete(locked, m("1.00"), newReference()));
                }
                return transfersPerThread;
              }));
    }
    start.countDown();
    int completed = 0;
    for (Future<Integer> result : results) {
      completed += result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    assertThat(completed).isEqualTo(2 * threadsPerDirection * transfersPerThread);
    assertBalances(w[0], "1000.00", "0.00");
    assertBalances(w[1], "1000.00", "0.00");
    assertThat(available(w[0]).add(available(w[1]))).isEqualByComparingTo("2000.00");
  }

  /**
   * The one statement locks both rows and returns them in PostgreSQL's uuid order, whichever way
   * round the ids are given; the service still tells sender from recipient by id.
   */
  @Test
  void oneStatementLocksBothRowsInTheDatabasesIdOrder() throws Exception {
    UUID[] w = twoWallets(null);
    List<UUID> ascending =
        jdbc.queryForList(
            "SELECT id FROM wallet.wallets WHERE id IN (?, ?) ORDER BY id", UUID.class, w[0], w[1]);

    for (UUID[] pair : new UUID[][] {{w[0], w[1]}, {w[1], w[0]}}) {
      transactions.executeWithoutResult(
          status -> {
            List<UUID> locked =
                walletRepository.lockBothForUpdate(pair[0], pair[1]).stream()
                    .map(WalletRow::id)
                    .toList();
            assertThat(locked).isEqualTo(ascending);
            // Both rows are held: another transaction cannot take either.
            for (UUID id : pair) {
              assertThatThrownBy(() -> lockNoWait(id))
                  .isInstanceOfSatisfying(
                      java.sql.SQLException.class,
                      e -> assertThat(e.getSQLState()).isEqualTo("55P03"));
            }
          });
      TransferWalletView sender =
          inMoneyTransaction(pair[0], pair[1], LockedTransferWallets::sender);
      assertThat(sender.walletId()).isEqualTo(pair[0]);
    }
  }

  private void lockNoWait(UUID walletId) throws java.sql.SQLException {
    try (java.sql.Connection other = openTransaction()) {
      queryOne(
          other,
          "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE NOWAIT",
          UUID.class,
          walletId);
    }
  }

  // Propagation -----------------------------------------------------------------------------------

  @Test
  void moneyOperationsNeedTheCallersTransactionAndThePrecheckRunsOutsideOne() throws Exception {
    Account sender = withWallet();
    funded(sender, "500.00");
    UUID wallet = walletId(sender);

    assertThatThrownBy(() -> transfers.lock(wallet, UUID.randomUUID()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(
            () -> transactions.execute(status -> transfers.precheck(sender.id(), m("1.00"))))
        .isInstanceOf(IllegalTransactionStateException.class);

    TransferPrecheck result = transfers.precheck(sender.id(), m("500.00"));
    TransferWalletView view = result.sender();
    assertThat(result.limitDate())
        .isEqualTo(
            LocalDate.ofInstant(mutableClock.instant(), java.time.ZoneId.of("Asia/Bangkok")));
    assertThat(view.walletId()).isEqualTo(wallet);
    assertThat(view.createdAt())
        .isEqualTo(
            jdbc.queryForObject(
                    "SELECT created_at FROM wallet.wallets WHERE id = ?",
                    java.time.OffsetDateTime.class,
                    wallet)
                .toInstant());
    assertThatThrownBy(() -> transfers.precheck(sender.id(), m("500.01")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
  }

  /** Funds a wallet through the real deposit path of its owner. */
  private void fundWallet(UUID walletId, String amount) throws Exception {
    funded(ownerOf(walletId), amount);
  }

  private Account ownerOf(UUID walletId) throws Exception {
    return signedInAgain(new Account(customerOfWallet(walletId), null));
  }

  // Review fixes ---------------------------------------------------------------------------------

  /** Security L1: lock() refuses a transaction that did not apply the D14 timeouts. */
  @Test
  void theLockRefusesATransactionWithoutTheTimeouts() throws Exception {
    UUID[] w = twoWallets("100.00");

    assertThatThrownBy(() -> transactions.execute(status -> transfers.lock(w[0], w[1])))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("D14");
    // With them, the same lock works; the mark did not leak from or into either transaction.
    assertThat(inMoneyTransaction(w[0], w[1], LockedTransferWallets::sender).walletId())
        .isEqualTo(w[0]);
    assertThatThrownBy(() -> transactions.execute(status -> transfers.lock(w[0], w[1])))
        .isInstanceOf(IllegalStateException.class);
  }

  /** Security L2: wallets locked in one transaction cannot be used in a later one. */
  @Test
  void lockedWalletsFromAnEarlierTransactionAreRefusedAndNothingIsWritten() throws Exception {
    UUID[] w = twoWallets("1000.00");
    LockedTransferWallets stale = inMoneyTransaction(w[0], w[1], locked -> locked);
    long senderVersion = version(w[0]);

    for (int operation = 0; operation < 3; operation++) {
      int which = operation;
      assertThatThrownBy(
              () ->
                  transactions.execute(
                      status -> {
                        timeouts.apply();
                        return switch (which) {
                          case 0 -> transfers.complete(stale, m("10.00"), newReference());
                          case 1 -> transfers.hold(stale, m("10.00"));
                          default ->
                              transfers.approve(stale, m("10.00"), LocalDate.now(), newReference());
                        };
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("another transaction");
    }
    assertBalances(w[0], "1000.00", "0.00");
    assertBalances(w[1], "0.00", "0.00");
    assertThat(version(w[0])).isEqualTo(senderVersion);
  }

  /**
   * Code review 3 (MVP-ADR-24, criterion 15): an approval that finds a wallet frozen or closed
   * releases the hold on its stored day, posts nothing, and says which wallet.
   */
  @Test
  void anApprovalWithAnUnavailableWalletReleasesTheHoldOnItsStoredDay() throws Exception {
    String[][] cases = {
      {"recipient", "FROZEN", "RECIPIENT_FROZEN"},
      {"sender", "FROZEN", "SENDER_FROZEN"},
      {"sender", "CLOSED", "SENDER_CLOSED"}
    };
    for (String[] c : cases) {
      UUID[] w = twoWallets("1000.00");
      mutableClock.set(BEFORE_MIDNIGHT);
      LocalDate limitDate =
          inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("300.00")));
      int senderPostings = ledgerTransactions(w[0]);
      jdbc.update(
          "UPDATE wallet.wallets SET status = ? WHERE id = ?",
          c[1],
          c[0].equals("sender") ? w[0] : w[1]);

      mutableClock.set(AFTER_MIDNIGHT);
      ApprovalOutcome outcome =
          inMoneyTransaction(
              w[0],
              w[1],
              locked -> transfers.approve(locked, m("300.00"), limitDate, newReference()));

      assertThat(outcome)
          .as(c[2])
          .isEqualTo(
              new ApprovalOutcome.ReleasedWalletUnavailable(UnavailableWallet.valueOf(c[2])));
      assertBalances(w[0], "1000.00", "0.00");
      assertBalances(w[1], "0.00", "0.00");
      assertUsage(w[0], DAY, "0.00", "0.00");
      assertThat(transferUsage(w[0], NEXT_DAY)).isEmpty();
      assertThat(ledgerTransactions(w[0])).isEqualTo(senderPostings);
      assertThat(ledgerTransactions(w[1])).isZero();
    }
  }

  /** Item 9: the V3 guard lets a CLOSED wallet's balances change, so a hold can leave review. */
  @Test
  void aReleaseWorksOnAClosedSender() throws Exception {
    UUID[] w = twoWallets("1000.00");
    LocalDate limitDate =
        inMoneyTransaction(w[0], w[1], locked -> transfers.hold(locked, m("10.00")));
    jdbc.update("UPDATE wallet.wallets SET status = 'CLOSED' WHERE id = ?", w[0]);

    inMoneyTransaction(
        w[0],
        w[1],
        locked -> {
          transfers.release(locked, m("10.00"), limitDate);
          return null;
        });

    assertBalances(w[0], "1000.00", "0.00");
    assertUsage(w[0], limitDate, "0.00", "0.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM wallet.wallets WHERE id = ?", String.class, w[0]))
        .isEqualTo("CLOSED");
  }

  /**
   * Item 9: a completion's limitDate is the day its usage was counted on, either side of 17:00Z.
   */
  @Test
  void aCompletionCountsOnTheBangkokDayItReturns() throws Exception {
    UUID[] w = twoWallets("1000.00");

    mutableClock.set(BEFORE_MIDNIGHT);
    TransferSettlement before =
        inMoneyTransaction(
            w[0], w[1], locked -> transfers.complete(locked, m("10.00"), newReference()));
    mutableClock.set(AFTER_MIDNIGHT);
    TransferSettlement after =
        inMoneyTransaction(
            w[0], w[1], locked -> transfers.complete(locked, m("20.00"), newReference()));

    assertThat(before.limitDate()).isEqualTo(DAY);
    assertThat(after.limitDate()).isEqualTo(NEXT_DAY);
    assertUsage(w[0], DAY, "10.00", "0.00");
    assertUsage(w[0], NEXT_DAY, "20.00", "0.00");
    assertBalances(w[0], "970.00", "0.00");
    assertBalances(w[1], "30.00", "0.00");
  }

  /**
   * DB review L1: the lock order is deterministic, lower id first. While H holds the higher-id
   * wallet, the one statement has already locked the lower-id one and waits; while H holds the
   * lower-id wallet, it waits there and has not touched the higher-id one.
   */
  @Test
  void theOneStatementLocksTheLowerIdFirst() throws Exception {
    UUID[] w = twoWallets(null);
    List<UUID> ascending =
        jdbc.queryForList(
            "SELECT id FROM wallet.wallets WHERE id IN (?, ?) ORDER BY id", UUID.class, w[0], w[1]);
    UUID lower = ascending.get(0);
    UUID higher = ascending.get(1);

    // H holds the higher one: T takes the lower one, then waits.
    try (java.sql.Connection holder = openTransaction()) {
      queryOne(holder, "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE", UUID.class, higher);
      Future<?> waiting = lockBothInBackground(w[0], w[1]);
      assertThatThrownBy(() -> lockNoWait(lower))
          .isInstanceOfSatisfying(
              java.sql.SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55P03"));
      holder.rollback();
      waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // H holds the lower one: T waits there, so the higher one is still free.
    try (java.sql.Connection holder = openTransaction()) {
      queryOne(holder, "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE", UUID.class, lower);
      Future<?> waiting = lockBothInBackground(w[1], w[0]);
      lockNoWait(higher);
      holder.rollback();
      waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  /** Starts the one-statement lock in another thread and returns once it waits on a lock. */
  private Future<?> lockBothInBackground(UUID first, UUID second) {
    java.util.concurrent.atomic.AtomicInteger pid = new java.util.concurrent.atomic.AtomicInteger();
    Future<?> future =
        pool.submit(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                      walletRepository.lockBothForUpdate(first, second);
                    }));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (pid.get() != 0 && "Lock".equals(waitEventType(pid.get()))) {
        return future;
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("the lock statement never waited");
  }

  private String waitEventType(int pid) {
    return jdbc.queryForObject(
        "SELECT coalesce(wait_event_type, '') FROM pg_stat_activity WHERE pid = ?",
        String.class,
        pid);
  }
}
