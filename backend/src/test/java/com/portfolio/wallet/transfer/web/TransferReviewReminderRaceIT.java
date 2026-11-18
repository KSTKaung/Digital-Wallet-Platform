package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.support.OutboxRows.Row;
import com.portfolio.wallet.transfer.application.ReviewReminderTestIds;
import com.portfolio.wallet.transfer.application.TransferReviewReminderService;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The 12-hour reminder racing a staff decision (documentations/06 MVP-ADR-24 "Expiry", reminder
 * amendment: "The job takes no money lock and never changes a transfer, so a transfer decided at
 * the same moment as a run can still get its reminder (accepted)"; doc 08 §4C.4 criteria 11, 12,
 * 16; PR 5b step 13).
 *
 * <p>Whichever comes first, the decision is the transfer's only outcome: one terminal state, one
 * review row, one audit row, the hold captured or released exactly once, and the reminder changes
 * none of it. The reminder itself is allowed per decision: written when the decision commits after
 * the reminder re-read the transfer, not written when it commits before; never twice, and never
 * waiting on the decision's locks.
 *
 * <p>Deterministic: a latch holds one side at a fixed point (the approval after it locked the
 * transfer row; the reminder before or after its re-read of the transfer), the other side runs to
 * completion within a bounded wait, then the first goes on. The job runs over the shared test
 * database, so every assertion reads only this test's own rows; each test moves its still-held
 * transfers past their deadline at the end, so later runs no longer remind them.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferReviewReminderRaceIT extends TransferReviewFlowSupport {

  private static final long WAIT_SECONDS = 30;
  private static final long TWELVE_HOURS = Duration.ofHours(12).toSeconds();
  private static final String KIND = "TRANSFER_REVIEW_EXPIRING";
  private static final String REASON = "Unusual activity";

  @Autowired TransferReviewReminderService reminders;

  private UUID heldHere;

  @AfterEach
  void noLongerACandidate() throws Exception {
    Mockito.reset(transferRowsSpy);
    if (heldHere != null && "PENDING_REVIEW".equals(transferRow(heldHere).get("status"))) {
      overdue(heldHere, 60);
    }
  }

  /** A transfer held 12 hours and 2 minutes ago, with its usual 24-hour deadline. */
  private UUID heldTwelveHoursAgo(Account sender, Account recipient) throws Exception {
    UUID id = held(sender, recipient);
    heldHere = id;
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET held_at = now() - make_interval(secs => ?),"
                    + " review_expires_at = now() - make_interval(secs => ?) + interval '24 hours'"
                    + " WHERE id = ?",
                (double) TWELVE_HOURS + 120,
                (double) TWELVE_HOURS + 120,
                id));
    return id;
  }

  /** The transfer's reminder rows: officer → event id. */
  private Map<UUID, UUID> reminderRows(UUID transferId) {
    String reference = (String) transferRow(transferId).get("reference");
    Map<UUID, UUID> rows = new HashMap<>();
    jdbc.query(
        "SELECT user_id, event_id FROM notification.notifications"
            + " WHERE kind = ? AND payload ->> 'reference' = ?",
        rs -> {
          UUID user = rs.getObject("user_id", UUID.class);
          assertThat(rows.put(user, rs.getObject("event_id", UUID.class)))
              .as("one reminder per officer")
              .isNull();
        },
        KIND,
        reference);
    return rows;
  }

  private Set<UUID> activeOfficers() {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT id FROM auth.users WHERE role = 'COMPLIANCE_OFFICER' AND status = 'ACTIVE'",
            UUID.class));
  }

  private void assertOneReminderPerActiveOfficer(UUID transferId) {
    Map<UUID, UUID> rows = reminderRows(transferId);
    assertThat(rows.keySet()).isEqualTo(activeOfficers());
    rows.forEach(
        (officer, event) ->
            assertThat(event).isEqualTo(ReviewReminderTestIds.of(transferId, officer)));
  }

  private List<String> emailKinds(Account account) {
    return transferEmails(account).stream().map(Row::kind).toList();
  }

  /** The decision is the only outcome: one state, one review row, one audit row, money once. */
  private void assertOnlyTheDecision(UUID id, Account sender, Account recipient, String status) {
    assertThat(transferRow(id)).containsEntry("status", status);
    assertThat(reviewRows(id)).hasSize(1);
    assertThat(reviewAuditsOf(id)).hasSize(1);
    String reference = (String) transferRow(id).get("reference");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    if (status.equals("COMPLETED")) {
      assertBalances(sender, "700.00", "0.00");
      assertBalances(recipient, "1300.00", "0.00");
      assertThat(transferUsed(sender)).isEqualByComparingTo(HOLD);
      assertThat(ledgerTransactions(reference)).isOne();
      assertBalancedPosting(reference, HOLD);
      assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_SENT");
      assertThat(emailKinds(recipient)).containsExactly("TRANSFER_RECEIVED");
    } else {
      assertBalances(sender, "2000.00", "0.00");
      assertBalances(recipient, "0.00", "0.00");
      assertThat(transferUsed(sender)).isEqualByComparingTo("0");
      assertThat(ledgerTransactions(reference)).isZero();
      assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_REJECTED");
      assertThat(transferEmails(recipient)).isEmpty();
    }
  }

  /** The start of the reminder's failure log line for this transfer. */
  private static String failureLine(UUID id) {
    return "TRANSFER_REVIEW_REMINDER_FAILED: reminder not written, retried by the next run:"
        + " transferId="
        + id;
  }

  /** Runs {@code work} on another thread and waits for it, at most {@link #WAIT_SECONDS}. */
  private static <T> T onAnotherThread(java.util.concurrent.Callable<T> work) throws Exception {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return work.call();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * An approval holds the transfer row ({@code FOR UPDATE}) inside its open transaction: a reminder
   * run neither waits on it nor changes the transfer, and writes the reminder (the transfer is
   * still held in every committed state it can see). The approval then commits as the only outcome,
   * and a later run adds nothing for the now decided transfer.
   */
  @Test
  void aReminderRunDoesNotWaitOnAnApprovalHoldingTheTransferRow(CapturedOutput output)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = heldTwelveHoursAgo(sender, recipient);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object row = invocation.callRealMethod();
              locked.countDown();
              assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
              return row;
            })
        .when(transferRowsSpy)
        .lockHeldForReview(id);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    MockHttpServletResponse approval;
    try {
      Future<MockHttpServletResponse> approving = pool.submit(() -> approveReview(id, reviewer));
      assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

      // The run finishes while the approval still holds the row: it took no lock on it.
      pool.submit(reminders::remindDue).get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertOneReminderPerActiveOfficer(id);
      assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");
      assertThat(reviewRows(id)).isEmpty();

      release.countDown();
      approval = approving.get(WAIT_SECONDS, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    assertThat(approval.getStatus()).as(approval.getContentAsString()).isEqualTo(200);
    assertThat(body(approval).get("status").asString()).isEqualTo("COMPLETED");
    assertOnlyTheDecision(id, sender, recipient, "COMPLETED");
    Map<UUID, UUID> written = reminderRows(id);
    reminders.remindDue();
    assertThat(reminderRows(id)).as("a decided transfer gets nothing more").isEqualTo(written);
    assertThat(output.getAll()).doesNotContain(failureLine(id));
  }

  /**
   * A rejection commits after the run found the transfer due and before its transaction re-reads
   * it: the re-read finds it no longer held, so no reminder is written, and the rejection is the
   * only outcome.
   */
  @Test
  void aDecisionCommittedBeforeTheReReadGetsNoReminder(CapturedOutput output) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = heldTwelveHoursAgo(sender, recipient);
    AtomicReference<MockHttpServletResponse> rejection = new AtomicReference<>();
    doAnswer(
            invocation -> {
              rejection.set(onAnotherThread(() -> rejectReview(id, REASON, reviewer)));
              return invocation.callRealMethod();
            })
        .when(transferRowsSpy)
        .findAwaitingReminder(eq(id), anyLong());

    reminders.remindDue();

    assertThat(rejection.get()).as("the rejection ran inside the reminder's window").isNotNull();
    assertThat(rejection.get().getStatus()).as(rejection.get().getContentAsString()).isEqualTo(200);
    assertThat(reminderRows(id)).isEmpty();
    assertOnlyTheDecision(id, sender, recipient, "REJECTED");
    assertThat(transferRow(id)).containsEntry("rejection_reason_category", "REVIEW_DECLINED");
    Mockito.reset(transferRowsSpy);
    reminders.remindDue();
    assertThat(reminderRows(id)).isEmpty();
    assertThat(output.getAll()).doesNotContain(failureLine(id));
  }

  /**
   * An approval commits after the run's transaction re-read the transfer and before it writes the
   * reminders: the reminders are still written, once per active officer (accepted: the officer then
   * finds the transfer decided), and the approval stays the only outcome; later runs add nothing.
   */
  @Test
  void aDecisionCommittedAfterTheReReadStillGetsItsReminderOnceAndChangesNothing(
      CapturedOutput output) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = heldTwelveHoursAgo(sender, recipient);
    AtomicReference<MockHttpServletResponse> approval = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Object current = invocation.callRealMethod();
              // The reminder's transaction holds no lock the approval needs: it completes now.
              approval.set(onAnotherThread(() -> approveReview(id, reviewer)));
              return current;
            })
        .when(transferRowsSpy)
        .findAwaitingReminder(eq(id), anyLong());

    reminders.remindDue();

    assertThat(approval.get()).as("the approval ran inside the reminder's window").isNotNull();
    assertThat(approval.get().getStatus()).as(approval.get().getContentAsString()).isEqualTo(200);
    assertOneReminderPerActiveOfficer(id);
    assertOnlyTheDecision(id, sender, recipient, "COMPLETED");
    Map<UUID, UUID> written = reminderRows(id);
    Mockito.reset(transferRowsSpy);
    reminders.remindDue();
    reminders.remindDue();
    assertThat(reminderRows(id)).isEqualTo(written);
    assertThat(output.getAll()).doesNotContain(failureLine(id));
  }
}
