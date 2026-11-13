package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.support.OutboxRows.Row;
import com.portfolio.wallet.transfer.application.TransferExpiryService;
import com.portfolio.wallet.transfer.persistence.TransferRepository.LockMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * Staff review actions racing each other, the customer cancel and the expiry job (documentations/06
 * MVP-ADR-24 "Concurrency", "Concurrent staff action and expiry" (mandatory); doc 08 §4C.4 criteria
 * 11, 14a, 15): whichever wins, there is exactly one final outcome, one terminal state, one review
 * row, one audit row and one set of emails, and the hold is captured or released exactly once; the
 * loser gets {@code 409} and writes nothing.
 *
 * <p>Deterministic where possible: a latch holds the winner just after it locked the transfer row,
 * the loser is started and seen waiting on that lock in {@code pg_stat_activity} (or, for the
 * expiry job, skipping it), then the winner goes on. The races at the deadline itself start both
 * sides together and accept either winner. Every assertion reads only this test's own rows.
 */
class TransferStaffReviewRaceIT extends TransferReviewFlowSupport {

  private static final long WAIT_SECONDS = 60;
  private static final String REASON = "Unusual activity";

  @Autowired TransferExpiryService expiry;

  private String status(UUID id) {
    return (String) transferRow(id).get("status");
  }

  private List<String> emailKinds(Account account) {
    return transferEmails(account).stream().map(Row::kind).toList();
  }

  /**
   * Exactly one outcome {@code status} for {@code id}: one review row, one audit row, the emails of
   * that status, and the hold captured (completed) or released (otherwise) exactly once.
   */
  private void assertOneOutcome(UUID id, Account sender, Account recipient, String status) {
    assertThat(status(id)).isEqualTo(status);
    assertThat(reviewRows(id)).hasSize(1);
    assertThat(reviewAuditsOf(id)).hasSize(1);
    String reference = (String) transferRow(id).get("reference");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    if (status.equals("COMPLETED")) {
      assertBalances(sender, "700.00", "0.00");
      assertBalances(recipient, "1300.00", "0.00");
      assertThat(transferUsed(sender)).isEqualByComparingTo(HOLD);
      assertThat(ledgerTransactions(reference)).isOne();
      assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_SENT");
      assertThat(emailKinds(recipient)).containsExactly("TRANSFER_RECEIVED");
    } else {
      assertBalances(sender, "2000.00", "0.00");
      assertBalances(recipient, "0.00", "0.00");
      assertThat(transferUsed(sender)).isEqualByComparingTo("0");
      assertThat(ledgerTransactions(reference)).isZero();
      String kind =
          switch (status) {
            case "REJECTED" -> "TRANSFER_REJECTED";
            case "CANCELLED" -> "TRANSFER_CANCELLED";
            default -> "TRANSFER_EXPIRED";
          };
      assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", kind);
      assertThat(transferEmails(recipient)).isEmpty();
    }
  }

  private static void assertLost(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
    assertThat(response.getContentAsString()).contains("INVALID_TRANSFER_STATE");
  }

  /** Signals that the winner holds the transfer row, and lets it go on when released. */
  private record Gate(CountDownLatch locked, CountDownLatch release) {

    static Gate open() {
      return new Gate(new CountDownLatch(1), new CountDownLatch(1));
    }
  }

  private Gate gateOnReviewLock(UUID id) {
    Gate gate = Gate.open();
    doAnswer(
            invocation -> {
              Object row = invocation.callRealMethod();
              gate.locked().countDown();
              assertThat(gate.release().await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
              return row;
            })
        .when(transferRowsSpy)
        .lockHeldForReview(id);
    return gate;
  }

  private Gate gateOnCancelLock(UUID id, Account sender) {
    Gate gate = Gate.open();
    doAnswer(
            invocation -> {
              Object row = invocation.callRealMethod();
              gate.locked().countDown();
              assertThat(gate.release().await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
              return row;
            })
        .when(transferRowsSpy)
        .lockSentBy(id, sender.id());
    return gate;
  }

  private Gate gateOnJobLock(UUID id) {
    Gate gate = Gate.open();
    doAnswer(
            invocation -> {
              Object row = invocation.callRealMethod();
              gate.locked().countDown();
              assertThat(gate.release().await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
              return row;
            })
        .when(transferRowsSpy)
        .lockForUpdate(id, LockMode.SKIP_LOCKED);
    return gate;
  }

  /**
   * Runs {@code winner} until it holds the transfer row ({@code gate}), then {@code loser} until it
   * waits on that lock, then lets the winner finish; returns both results.
   */
  private <W, L> List<Object> winnerThenLoser(Gate gate, Callable<W> winner, Callable<L> loser)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<W> first = pool.submit(winner);
      assertThat(gate.locked().await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Future<L> second = pool.submit(loser);
      awaitLockWaiter();
      gate.release().countDown();
      W won = first.get(WAIT_SECONDS, TimeUnit.SECONDS);
      L lost = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
      return java.util.Arrays.asList(won, lost);
    } finally {
      gate.release().countDown();
      pool.shutdownNow();
      Mockito.reset(transferRowsSpy);
    }
  }

  // Approve against the customer cancel (MVP-ADR-24 test: "approve racing cancel") -------------

  @Test
  void anApprovalHoldingTheRowWinsAndTheCancelGets409() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    Gate gate = gateOnReviewLock(id);

    List<Object> results =
        winnerThenLoser(gate, () -> approveReview(id, reviewer), () -> cancel(id, sender));

    assertThat(((MockHttpServletResponse) results.get(0)).getStatus()).isEqualTo(200);
    assertLost((MockHttpServletResponse) results.get(1));
    assertOneOutcome(id, sender, recipient, "COMPLETED");
  }

  @Test
  void aCancelHoldingTheRowWinsAndTheApprovalGets409() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    Gate gate = gateOnCancelLock(id, sender);

    List<Object> results =
        winnerThenLoser(gate, () -> cancel(id, sender), () -> approveReview(id, reviewer));

    assertThat(((MockHttpServletResponse) results.get(0)).getStatus()).isEqualTo(200);
    assertLost((MockHttpServletResponse) results.get(1));
    assertOneOutcome(id, sender, recipient, "CANCELLED");
  }

  /** Started together, repeatedly: exactly one wins every time. */
  @Test
  void anApprovalAndACancelStartedTogetherEndWithExactlyOneOutcome() throws Exception {
    for (int round = 0; round < 4; round++) {
      Account sender = funded("2000.00");
      Account recipient = withWallet();
      Account reviewer = officer();
      UUID id = held(sender, recipient);

      List<MockHttpServletResponse> responses =
          together(() -> approveReview(id, reviewer), () -> cancel(id, sender));

      String status = status(id);
      assertThat(status).isIn("COMPLETED", "CANCELLED");
      assertThat(responses.get(0).getStatus()).isEqualTo(status.equals("COMPLETED") ? 200 : 409);
      assertThat(responses.get(1).getStatus()).isEqualTo(status.equals("CANCELLED") ? 200 : 409);
      assertOneOutcome(id, sender, recipient, status);
    }
  }

  // Two reviewers -------------------------------------------------------------------------------

  @Test
  void anApprovalHoldingTheRowWinsAgainstAnotherReviewersRejection() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    Gate gate = gateOnReviewLock(id);
    Account first = officer();
    Account second = officer();

    List<Object> results =
        winnerThenLoser(
            gate, () -> approveReview(id, first), () -> rejectReview(id, REASON, second));

    assertThat(((MockHttpServletResponse) results.get(0)).getStatus()).isEqualTo(200);
    assertLost((MockHttpServletResponse) results.get(1));
    assertOneOutcome(id, sender, recipient, "COMPLETED");
    assertThat(reviewRows(id).getFirst()).containsEntry("actor_user_id", first.id());
  }

  @Test
  void anApprovalAndARejectionStartedTogetherEndWithExactlyOneOutcome() throws Exception {
    for (int round = 0; round < 4; round++) {
      Account sender = funded("2000.00");
      Account recipient = withWallet();
      Account first = officer();
      Account second = officer();
      UUID id = held(sender, recipient);

      List<MockHttpServletResponse> responses =
          together(() -> approveReview(id, first), () -> rejectReview(id, REASON, second));

      String status = status(id);
      assertThat(status).isIn("COMPLETED", "REJECTED");
      assertThat(responses.get(0).getStatus()).isEqualTo(status.equals("COMPLETED") ? 200 : 409);
      assertThat(responses.get(1).getStatus()).isEqualTo(status.equals("REJECTED") ? 200 : 409);
      assertOneOutcome(id, sender, recipient, status);
    }
  }

  // Against the expiry job (criterion 14a, mandatory) -------------------------------------------

  /** The job holds the row: the approval waits, finds it EXPIRED and writes nothing (case 5). */
  @Test
  void anApprovalWaitingOnTheJobsLockGets409AndTheJobsOutcomeStands() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    Gate gate = gateOnJobLock(id);

    List<Object> results =
        winnerThenLoser(gate, expiry::expireDue, () -> approveReview(id, reviewer));

    assertLost((MockHttpServletResponse) results.get(1));
    assertOneOutcome(id, sender, recipient, "EXPIRED");
    assertThat(reviewRows(id).getFirst())
        .containsEntry("requested_action", null)
        .containsEntry("actor_user_id", null);
    assertThat(reviewAuditsOf(id).getFirst()).containsEntry("actor_type", "SYSTEM");
  }

  @Test
  void aRejectionWaitingOnTheJobsLockGets409AndWritesNothing() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    Gate gate = gateOnJobLock(id);

    List<Object> results =
        winnerThenLoser(gate, expiry::expireDue, () -> rejectReview(id, REASON, reviewer));

    assertLost((MockHttpServletResponse) results.get(1));
    assertOneOutcome(id, sender, recipient, "EXPIRED");
    assertThat(reviewRows(id).getFirst()).containsEntry("reason", null);
  }

  /**
   * The reviewer holds the row after the deadline: the job skips it without waiting, and the
   * reviewer's own late action expires it (case 6), once.
   */
  @Test
  void theJobSkipsATransferWhoseReviewerHoldsTheLock() throws Exception {
    for (String action : List.of("approve", "reject")) {
      Account sender = funded("2000.00");
      Account recipient = withWallet();
      Account reviewer = officer();
      UUID id = held(sender, recipient);
      overdue(id, 60);
      Gate gate = gateOnReviewLock(id);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      MockHttpServletResponse response;
      try {
        Future<MockHttpServletResponse> review =
            pool.submit(
                () ->
                    action.equals("approve")
                        ? approveReview(id, reviewer)
                        : rejectReview(id, REASON, reviewer));
        assertThat(gate.locked().await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        pool.submit(expiry::expireDue).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(status(id)).isEqualTo("PENDING_REVIEW");
        assertThat(reviewRows(id)).isEmpty();

        gate.release().countDown();
        response = review.get(WAIT_SECONDS, TimeUnit.SECONDS);
      } finally {
        gate.release().countDown();
        pool.shutdownNow();
        Mockito.reset(transferRowsSpy);
      }
      assertLost(response);
      expiry.expireDue();
      assertOneOutcome(id, sender, recipient, "EXPIRED");
      assertThat(reviewRows(id).getFirst())
          .containsEntry("requested_action", action.equals("approve") ? "APPROVE" : "REJECT")
          .containsEntry("actor_user_id", reviewer.id())
          .containsEntry("reason", null);
      assertThat(reviewAuditsOf(id).getFirst()).containsEntry("actor_type", "STAFF");
    }
  }

  /**
   * Started together right at the deadline, repeatedly: whichever side wins and whichever side of
   * the deadline the decision falls on, exactly one outcome.
   */
  @Test
  void aReviewRacingTheJobAtTheDeadlineEndsWithExactlyOneOutcome() throws Exception {
    for (int round = 0; round < 4; round++) {
      boolean approve = round % 2 == 0;
      Account sender = funded("2000.00");
      Account recipient = withWallet();
      Account reviewer = officer();
      UUID id = held(sender, recipient);
      // Rounds 0 and 1: the deadline one second ahead; rounds 2 and 3: just passed.
      overdue(id, round < 2 ? -1 : 0);
      Instant deadline =
          jdbc.queryForObject(
                  "SELECT review_expires_at FROM transfer.transfers WHERE id = ?",
                  OffsetDateTime.class,
                  id)
              .toInstant();

      List<Object> results =
          togetherAt(
              deadline,
              expiry::expireDue,
              () -> approve ? approveReview(id, reviewer) : rejectReview(id, REASON, reviewer));
      // A job that ran just before the deadline found nothing due; the next run settles it.
      expiry.expireDue();

      MockHttpServletResponse response = (MockHttpServletResponse) results.get(1);
      String status = status(id);
      String won = approve ? "COMPLETED" : "REJECTED";
      assertThat(status).isIn(won, "EXPIRED");
      assertThat(response.getStatus()).isEqualTo(status.equals(won) ? 200 : 409);
      assertOneOutcome(id, sender, recipient, status);
    }
  }

  // Helpers -------------------------------------------------------------------------------------

  private List<MockHttpServletResponse> together(
      Callable<MockHttpServletResponse> a, Callable<MockHttpServletResponse> b) throws Exception {
    List<Object> results = togetherAt(null, a, b);
    return List.of(
        (MockHttpServletResponse) results.get(0), (MockHttpServletResponse) results.get(1));
  }

  private static List<Object> togetherAt(Instant at, Callable<?> a, Callable<?> b)
      throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(() -> startThen(start, at, a));
      Future<?> second = pool.submit(() -> startThen(start, at, b));
      return java.util.Arrays.asList(
          first.get(WAIT_SECONDS, TimeUnit.SECONDS), second.get(WAIT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  private static Object startThen(CyclicBarrier start, Instant at, Callable<?> work)
      throws Exception {
    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
    if (at != null) {
      long millis = Duration.between(Instant.now(), at).toMillis();
      if (millis > 0) {
        Thread.sleep(millis);
      }
    }
    return work.call();
  }

  /** Waits until some session waits on a row lock of the transfers table. */
  private void awaitLockWaiter() {
    await()
        .atMost(Duration.ofSeconds(WAIT_SECONDS))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND query LIKE '%transfer.transfers%'",
                        Integer.class)
                    > 0);
  }
}
