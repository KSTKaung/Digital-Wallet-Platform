package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.OutboxRows;
import com.portfolio.wallet.transfer.application.TransferExpiryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Concurrency of transfers end to end, through HTTP, the risk assessment, the idempotency engine
 * and the money transaction (documentations/06 MVP-ADR-03, -08, -18, -22, -24; doc 08 §4C.4
 * criteria 2, 6–8, 10, 11, 19; PR 5a step 7).
 *
 * <ul>
 *   <li>N concurrent transfers from one sender never overdraw and never exceed the daily limit
 *       (used + reserved, BASIC 100,000.00), with holds counted as reserved;
 *   <li>opposite directions do not deadlock and end in exactly the state a serial run would;
 *   <li>a seeded random mix of completions, holds, cancels (repeated, late and racing) and expiry
 *       runs keeps, at every point checked, each wallet's reserved balance and each day's reserved
 *       usage equal to the sum of that sender's open holds on that day, each day's used equal to
 *       its completed transfers, money conserved, Σ DEBIT = Σ CREDIT, available + reserved = ledger
 *       balance (MVP-ADR-03 check 1), and exactly one review row, audit row and email per decided
 *       hold: every hold released or captured exactly once (Q4).
 * </ul>
 *
 * Every assertion is an invariant or an outcome that does not depend on the interleaving, so the
 * tests are deterministic although the scheduling is not. No sleeps: a latch starts every task at
 * once, and every wait is bounded. Every assertion reads only this test's own rows.
 */
class TransferConcurrencyIT extends TransferReviewFlowSupport {

  private static final long TIMEOUT_SECONDS = 120;
  private static final String HOLD_ANY_RECIPIENT = "1400.00";
  private static final BigDecimal BASIC_DAILY = new BigDecimal("100000.00");
  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

  @Autowired TransferExpiryService expiry;

  private ExecutorService pool;

  @BeforeEach
  void startPool() {
    pool = Executors.newFixedThreadPool(20);
  }

  @AfterEach
  void stopPool() {
    pool.shutdownNow();
  }

  // Criterion 10: no overdraft, no limit excess ------------------------------------------------

  /** 20 at once, room for exactly 5: 5 complete, 15 are refused, the balance never negative. */
  @Test
  void twentyConcurrentTransfersFromOneSenderNeverOverdraw() throws Exception {
    Account sender = funded("5000.00");
    Account first = withWallet();
    Account second = withWallet();

    List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      Account recipient = i % 2 == 0 ? first : second;
      tasks.add(() -> outcome(transfer(sender, recipient, ALLOW)));
    }
    List<String> outcomes = race(tasks);

    assertThat(outcomes).filteredOn("201:COMPLETED"::equals).hasSize(5);
    assertThat(outcomes).filteredOn("409:INSUFFICIENT_BALANCE"::equals).hasSize(15);
    assertBalances(sender, "0.05", "0.00");
    assertThat(available(first).add(available(second))).isEqualByComparingTo("4999.95");
    assertThat(transferUsed(sender)).isEqualByComparingTo("4999.95");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    assertTotalMatchesLedger(first);
    assertTotalMatchesLedger(second);
    assertOwnPostingsBalanced(List.of(sender, first, second));
    assertThat(statuses(sender)).containsOnly("COMPLETED").hasSize(5);
  }

  /**
   * The daily limit counts used + reserved under the lock: with 4,999.99 left on the BASIC
   * 100,000.00 limit, 20 transfers of 999.99 at once give exactly 5 completions.
   */
  @Test
  void twentyConcurrentTransfersNeverExceedTheDailyLimit() throws Exception {
    Account sender = funded("20000.00");
    Account recipient = withWallet();
    seedUsedToday(sender, "95000.00");

    List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      tasks.add(() -> outcome(transfer(sender, recipient, ALLOW)));
    }
    List<String> outcomes = race(tasks);

    assertThat(outcomes).filteredOn("201:COMPLETED"::equals).hasSize(5);
    assertThat(outcomes).filteredOn("409:DAILY_LIMIT_EXCEEDED"::equals).hasSize(15);
    LocalDate day = onlyLimitDate(sender);
    Map<String, Object> usage = transferUsage(sender, day).getFirst();
    assertThat((BigDecimal) usage.get("used")).isEqualByComparingTo("99999.95");
    assertThat((BigDecimal) usage.get("reserved")).isEqualByComparingTo("0");
    assertBalances(sender, "15000.05", "0.00");
    assertBalances(recipient, "4999.95", "0.00");
    assertOwnPostingsBalanced(List.of(sender, recipient));
  }

  /**
   * Completions and holds at once against the daily limit: used + reserved never passes it, the
   * reserved balance is the sum of the open holds, and every refused amount really did not fit
   * (usage only grows here, so the room left at the end is at most the room at the refusal).
   */
  @Test
  void concurrentHoldsAndCompletionsTogetherNeverExceedTheDailyLimit() throws Exception {
    Account sender = funded("20000.00");
    Account recipient = withWallet();
    seedUsedToday(sender, "95000.00");

    List<String> amounts = new ArrayList<>();
    List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      String amount = i % 2 == 0 ? ALLOW : HOLD_ANY_RECIPIENT;
      amounts.add(amount);
      tasks.add(() -> outcome(transfer(sender, recipient, amount)));
    }
    List<String> outcomes = race(tasks);

    assertThat(outcomes)
        .allSatisfy(
            o ->
                assertThat(o)
                    .isIn("201:COMPLETED", "201:PENDING_REVIEW", "409:DAILY_LIMIT_EXCEEDED"));
    assertThat(outcomes).contains("409:DAILY_LIMIT_EXCEEDED");
    BigDecimal completed = sum(sender, "COMPLETED");
    BigDecimal pending = sum(sender, "PENDING_REVIEW");
    LocalDate day = onlyLimitDate(sender);
    Map<String, Object> usage = transferUsage(sender, day).getFirst();
    BigDecimal used = (BigDecimal) usage.get("used");
    BigDecimal reservedUsage = (BigDecimal) usage.get("reserved");
    assertThat(used).isEqualByComparingTo(new BigDecimal("95000.00").add(completed));
    assertThat(reservedUsage).isEqualByComparingTo(pending);
    assertThat(used.add(reservedUsage)).isLessThanOrEqualTo(BASIC_DAILY);
    BigDecimal room = BASIC_DAILY.subtract(used).subtract(reservedUsage);
    for (int i = 0; i < outcomes.size(); i++) {
      if (outcomes.get(i).equals("409:DAILY_LIMIT_EXCEEDED")) {
        assertThat(room)
            .as("a refused amount did not fit")
            .isLessThan(new BigDecimal(amounts.get(i)));
      }
    }
    assertBalances(
        sender,
        new BigDecimal("20000.00").subtract(completed).subtract(pending).toPlainString(),
        pending.toPlainString());
    assertBalances(recipient, completed.toPlainString(), "0.00");
    assertOwnPostingsBalanced(List.of(sender, recipient));
  }

  // Criterion 10: opposite directions ----------------------------------------------------------

  /**
   * A → B and B → A at once, completions and holds mixed: no deadlock and no timeout (every request
   * is 201), and the end state is exactly the serial one, since the review policy makes each
   * outcome independent of the order.
   */
  @Test
  void oppositeDirectionsAtOnceNeverDeadlockAndConserveMoney() throws Exception {
    Account a = funded("20000.00");
    Account b = funded("20000.00");

    List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      Account from = i % 2 == 0 ? a : b;
      Account to = i % 2 == 0 ? b : a;
      String amount = (i / 2) % 2 == 0 ? ALLOW : HOLD_ANY_RECIPIENT;
      tasks.add(() -> outcome(transfer(from, to, amount)));
    }
    List<String> outcomes = race(tasks);

    assertThat(outcomes).filteredOn("201:COMPLETED"::equals).hasSize(10);
    assertThat(outcomes).filteredOn("201:PENDING_REVIEW"::equals).hasSize(10);
    // Each sent 5 x 999.99 and received the same; each holds 5 x 1,400.00.
    assertBalances(a, "13000.00", "7000.00");
    assertBalances(b, "13000.00", "7000.00");
    assertThat(transferReserved(a)).isEqualByComparingTo("7000.00");
    assertThat(transferReserved(b)).isEqualByComparingTo("7000.00");
    assertOwnPostingsBalanced(List.of(a, b));
  }

  // Criterion 11: the hold invariant under a random mix ---------------------------------------

  private enum Op {
    COMPLETE,
    HOLD,
    CANCEL,
    EXPIRE
  }

  /**
   * A seeded random mix of 48 operations on four accounts that send to each other: completions,
   * holds, cancels (of held, overdue, already decided and soon-to-be decided transfers, by the
   * sender) and expiry runs, all started at once. Then every remaining hold is expired. The
   * invariants of the class description hold after the race and after the settlement, and in the
   * end nothing is reserved anywhere.
   */
  @Test
  void aRandomMixOfCompletionsHoldsCancelsAndExpiryReleasesEveryHoldExactlyOnce() throws Exception {
    Random random = new Random(20261113L);
    List<Account> accounts = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      accounts.add(funded("10000.00"));
    }
    // Two holds per account before the race; the first of each is already overdue, so a cancel of
    // it expires it and the expiry job competes for it.
    List<UUID> holds = new ArrayList<>();
    Map<UUID, Account> senderOf = new HashMap<>();
    for (int i = 0; i < accounts.size(); i++) {
      Account sender = accounts.get(i);
      Account recipient = accounts.get((i + 1) % accounts.size());
      for (int h = 0; h < 2; h++) {
        UUID id = held(sender, recipient);
        holds.add(id);
        senderOf.put(id, sender);
        if (h == 0) {
          overdue(id, 60);
        }
      }
    }

    List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 48; i++) {
      Op op = Op.values()[random.nextInt(Op.values().length)];
      Account from = accounts.get(random.nextInt(accounts.size()));
      Account to = accounts.get((accounts.indexOf(from) + 1 + random.nextInt(3)) % 4);
      UUID target = holds.get(random.nextInt(holds.size()));
      tasks.add(
          switch (op) {
            case COMPLETE -> () -> outcome(transfer(from, to, ALLOW));
            case HOLD -> () -> outcome(transfer(from, to, HOLD_ANY_RECIPIENT));
            case CANCEL -> () -> outcome(cancel(target, senderOf.get(target)));
            case EXPIRE ->
                () -> {
                  expiry.expireDue();
                  return "EXPIRE";
                };
          });
    }
    List<String> outcomes = race(tasks);

    assertThat(outcomes)
        .as("no request failed with a timeout, a deadlock or any unexpected code")
        .allSatisfy(
            o ->
                assertThat(o)
                    .isIn(
                        "201:COMPLETED",
                        "201:PENDING_REVIEW",
                        "409:INSUFFICIENT_BALANCE",
                        "200:CANCELLED",
                        "409:INVALID_TRANSFER_STATE",
                        "EXPIRE"));
    // The seed gives a mix that reaches every path (fixed by the seed, not by the scheduling).
    assertThat(outcomes)
        .contains(
            "201:COMPLETED",
            "201:PENDING_REVIEW",
            "200:CANCELLED",
            "409:INVALID_TRANSFER_STATE",
            "EXPIRE");
    for (UUID id : holds) {
      if (holds.indexOf(id) % 2 == 0) {
        // Overdue before the race: a cancel or a run expired it, whichever came first.
        assertThat(transferRow(id)).containsEntry("status", "EXPIRED");
      }
    }
    assertHoldInvariants(accounts);

    // Settle: every hold still open becomes overdue and is expired.
    for (Account account : accounts) {
      for (UUID id : idsWithStatus(account, "PENDING_REVIEW")) {
        overdue(id, 60);
      }
    }
    for (int run = 0; run < 5 && openHolds(accounts) > 0; run++) {
      expiry.expireDue();
    }
    assertThat(openHolds(accounts)).isZero();
    assertHoldInvariants(accounts);
    for (Account account : accounts) {
      assertThat(reserved(account)).isEqualByComparingTo("0");
      assertThat(transferReserved(account)).isEqualByComparingTo("0");
    }
  }

  // Invariants ---------------------------------------------------------------------------------

  private void assertHoldInvariants(List<Account> accounts) {
    BigDecimal total = BigDecimal.ZERO;
    for (Account account : accounts) {
      List<Map<String, Object>> sent = sentTransfers(account);
      BigDecimal completedOut = BigDecimal.ZERO;
      BigDecimal pendingOut = BigDecimal.ZERO;
      Map<LocalDate, BigDecimal[]> perDay = new HashMap<>();
      int held = 0;
      int cancelled = 0;
      int expired = 0;
      for (Map<String, Object> row : sent) {
        String status = (String) row.get("status");
        BigDecimal amount = (BigDecimal) row.get("amount");
        LocalDate day = ((java.sql.Date) row.get("limit_date")).toLocalDate();
        BigDecimal[] usage =
            perDay.computeIfAbsent(day, d -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        UUID id = (UUID) row.get("id");
        String reference = (String) row.get("reference");
        switch (status) {
          case "COMPLETED" -> {
            completedOut = completedOut.add(amount);
            usage[0] = usage[0].add(amount);
            assertThat(ledgerTransactions(reference)).as("one posting").isOne();
            assertBalancedPosting(reference, amount.toPlainString());
            assertThat(reviewRows(id)).isEmpty();
          }
          case "PENDING_REVIEW" -> {
            pendingOut = pendingOut.add(amount);
            usage[1] = usage[1].add(amount);
            held++;
            assertThat(ledgerTransactions(reference)).isZero();
            assertThat(reviewRows(id)).isEmpty();
            assertThat(reviewAudits(id)).isZero();
          }
          case "CANCELLED", "EXPIRED" -> {
            held++;
            if (status.equals("CANCELLED")) {
              cancelled++;
            } else {
              expired++;
            }
            assertThat(ledgerTransactions(reference)).isZero();
            assertThat(reviewRows(id)).as("one review row for " + status).hasSize(1);
            assertThat(reviewAudits(id)).as("one audit row for " + status).isOne();
          }
          default -> throw new AssertionError("unexpected status " + status);
        }
      }
      BigDecimal completedIn = receivedCompleted(account);
      String who = "account " + accounts.indexOf(account);
      assertThat(reserved(account))
          .as(who + " reserved = open holds")
          .isEqualByComparingTo(pendingOut);
      assertThat(available(account))
          .as(who + " available")
          .isEqualByComparingTo(
              new BigDecimal("10000.00")
                  .subtract(completedOut)
                  .subtract(pendingOut)
                  .add(completedIn));
      assertTotalMatchesLedger(account);
      // Each day's usage: used = its completions, reserved = its open holds; no other day used.
      List<Map<String, Object>> days =
          jdbc.queryForList(
              "SELECT limit_date, used, reserved FROM wallet.daily_limit_usage"
                  + " WHERE wallet_id = ? AND operation = 'TRANSFER'",
              walletId(account));
      for (Map<String, Object> day : days) {
        LocalDate date = ((java.sql.Date) day.get("limit_date")).toLocalDate();
        BigDecimal[] expected =
            perDay.getOrDefault(date, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        assertThat((BigDecimal) day.get("used"))
            .as(who + " used on " + date)
            .isEqualByComparingTo(expected[0]);
        assertThat((BigDecimal) day.get("reserved"))
            .as(who + " reserved on " + date)
            .isEqualByComparingTo(expected[1]);
      }
      // Q9: one email per state change, to the sender; the receiver only on completion.
      List<String> kinds = transferEmails(account).stream().map(OutboxRows.Row::kind).toList();
      assertThat(Collections.frequency(kinds, "TRANSFER_HELD"))
          .as(who + " held emails")
          .isEqualTo(held);
      assertThat(Collections.frequency(kinds, "TRANSFER_CANCELLED")).isEqualTo(cancelled);
      assertThat(Collections.frequency(kinds, "TRANSFER_EXPIRED")).isEqualTo(expired);
      assertThat(Collections.frequency(kinds, "TRANSFER_SENT"))
          .isEqualTo(idsWithStatus(account, "COMPLETED").size());
      assertThat(Collections.frequency(kinds, "TRANSFER_RECEIVED"))
          .isEqualTo(
              jdbc.queryForObject(
                  "SELECT count(*) FROM transfer.transfers"
                      + " WHERE receiver_customer_id = ? AND status = 'COMPLETED'",
                  Integer.class,
                  account.id()));
      total = total.add(available(account)).add(reserved(account));
    }
    assertThat(total)
        .as("money conserved")
        .isEqualByComparingTo(
            new BigDecimal("10000.00").multiply(BigDecimal.valueOf(accounts.size())));
    assertOwnPostingsBalanced(accounts);
  }

  /** Σ DEBIT = Σ CREDIT over every ledger transaction that touches these accounts' wallets. */
  private void assertOwnPostingsBalanced(List<Account> accounts) {
    for (Account account : accounts) {
      Map<String, Object> sums =
          jdbc.queryForMap(
              """
              SELECT coalesce(sum(CASE WHEN e.entry_type = 'DEBIT' THEN e.amount END), 0) AS debits,
                     coalesce(sum(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount END), 0) AS credits
              FROM ledger.ledger_entries e
              WHERE e.transaction_id IN (
                SELECT e2.transaction_id FROM ledger.ledger_entries e2
                JOIN ledger.ledger_accounts a ON a.id = e2.account_id
                WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER')
              """,
              walletId(account));
      assertThat((BigDecimal) sums.get("debits"))
          .as("Σ debit = Σ credit")
          .isEqualByComparingTo((BigDecimal) sums.get("credits"));
      assertTotalMatchesLedger(account);
    }
  }

  // Helpers ------------------------------------------------------------------------------------

  /** Starts every task at once and returns each outcome; a task that throws fails the test. */
  private List<String> race(List<Callable<String>> tasks) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<String>> futures = new ArrayList<>();
    for (Callable<String> task : tasks) {
      futures.add(
          pool.submit(
              () -> {
                assertThat(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return task.call();
              }));
    }
    start.countDown();
    List<String> outcomes = new ArrayList<>();
    for (Future<String> future : futures) {
      outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
    return outcomes;
  }

  /** {@code status:STATUS} for a transfer body, {@code status:ERROR_CODE} for an error. */
  private String outcome(MockHttpServletResponse response) throws Exception {
    var json = body(response);
    String detail =
        json.has("errorCode") ? json.get("errorCode").asString() : json.get("status").asString();
    return response.getStatus() + ":" + detail;
  }

  /**
   * Sets this wallet's transfer usage to {@code used} on today's and tomorrow's Bangkok day, so the
   * test does not depend on which side of midnight its transfers fall.
   */
  private void seedUsedToday(Account account, String used) {
    LocalDate today = LocalDate.now(clock.withZone(BANGKOK));
    for (LocalDate day : List.of(today, today.plusDays(1))) {
      jdbc.update(
          "INSERT INTO wallet.daily_limit_usage (wallet_id, operation, limit_date, used)"
              + " VALUES (?, 'TRANSFER', ?, ?)",
          walletId(account),
          day,
          new BigDecimal(used));
    }
  }

  private LocalDate onlyLimitDate(Account sender) {
    List<LocalDate> days =
        jdbc.queryForList(
            "SELECT DISTINCT limit_date FROM transfer.transfers WHERE sender_customer_id = ?",
            LocalDate.class,
            sender.id());
    assertThat(days).as("all on one Bangkok day").hasSize(1);
    return days.getFirst();
  }

  private List<Map<String, Object>> sentTransfers(Account sender) {
    return jdbc.queryForList(
        "SELECT id, reference, status, amount, limit_date FROM transfer.transfers"
            + " WHERE sender_customer_id = ?",
        sender.id());
  }

  private List<String> statuses(Account sender) {
    return jdbc.queryForList(
        "SELECT status FROM transfer.transfers WHERE sender_customer_id = ?",
        String.class,
        sender.id());
  }

  private List<UUID> idsWithStatus(Account sender, String status) {
    return jdbc.queryForList(
        "SELECT id FROM transfer.transfers WHERE sender_customer_id = ? AND status = ?",
        UUID.class,
        sender.id(),
        status);
  }

  private int openHolds(List<Account> accounts) {
    return accounts.stream().mapToInt(a -> idsWithStatus(a, "PENDING_REVIEW").size()).sum();
  }

  private BigDecimal sum(Account sender, String status) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(amount), 0) FROM transfer.transfers"
            + " WHERE sender_customer_id = ? AND status = ?",
        BigDecimal.class,
        sender.id(),
        status);
  }

  private BigDecimal receivedCompleted(Account receiver) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(amount), 0) FROM transfer.transfers"
            + " WHERE receiver_customer_id = ? AND status = 'COMPLETED'",
        BigDecimal.class,
        receiver.id());
  }

  private int reviewAudits(UUID transferId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs WHERE action = 'TRANSFER_REVIEW' AND resource_id = ?",
        Integer.class,
        transferId.toString());
  }
}
