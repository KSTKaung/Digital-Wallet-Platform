package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.notification.application.ComplianceOfficerRecipients;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRepository.ReminderCandidate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The review reminder's run (documentations/06 MVP-ADR-24 "Expiry", "Jobs (Q11)"; doc 08 §4C.4
 * criteria 12, 16): officers read only when a candidate exists, reminders already recorded skipped
 * without a transaction, one transaction per transfer with the D14 timeouts first and an unlocked
 * re-read, one row per missing officer under the deterministic id with the Q9 values only, a
 * failure logged by id and class with the run going on, and the per-run maximum counting only
 * transfers that got new reminders.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferReviewReminderServiceTest {

  private static final Instant DEADLINE = Instant.parse("2026-11-14T05:00:00Z");
  private static final long TWELVE_HOURS = 43_200;
  private static final int PAGE = TransferReviewReminderService.PAGE_SIZE;

  private final TransferRepository transfers = mock(TransferRepository.class);
  private final ComplianceOfficerRecipients officers = mock(ComplianceOfficerRecipients.class);
  private final NotificationOutbox outbox = mock(NotificationOutbox.class);
  private final TransactionTimeouts timeouts = mock(TransactionTimeouts.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  private final UUID officerA = UUID.fromString("00000000-0000-4000-8000-00000000000a");
  private final UUID officerB = UUID.fromString("00000000-0000-4000-8000-00000000000b");

  private TransferReviewReminderService service(int maxPerRun) {
    return new TransferReviewReminderService(
        transfers,
        officers,
        outbox,
        timeouts,
        new TransferReviewProperties(Duration.ofHours(24), Duration.ofHours(12)),
        new TransferReviewReminderProperties(false, Duration.ofMinutes(1), maxPerRun),
        transactions);
  }

  private static ReminderCandidate candidate(int second) {
    return new ReminderCandidate(
        UUID.randomUUID(),
        "TRF-" + Integer.toHexString(0xA000 + second).toUpperCase(java.util.Locale.ROOT),
        new BigDecimal("1300.00"),
        "THB",
        DEADLINE.plusSeconds(second));
  }

  /** Each candidate is still one when read again, and every insert writes. */
  private void stillHeld(ReminderCandidate... candidates) {
    for (ReminderCandidate candidate : candidates) {
      when(transfers.findAwaitingReminder(candidate.id(), TWELVE_HOURS))
          .thenReturn(Optional.of(candidate));
    }
    when(outbox.enqueueOnce(any(), any(), any(), any())).thenReturn(true);
  }

  private static Map<String, String> values(ReminderCandidate candidate) {
    return Map.of("amount", "1300.00", "currency", "THB", "reference", candidate.reference());
  }

  @BeforeEach
  void setUp() {
    // The mock manager marks the transaction active while it runs, as a real one does.
    when(transactions.getTransaction(any()))
        .thenAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return new SimpleTransactionStatus();
            });
    doAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactions)
        .commit(any());
    doAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactions)
        .rollback(any());
    when(officers.activeComplianceOfficers()).thenReturn(List.of(officerA, officerB));
    when(outbox.recorded(anyCollection())).thenReturn(Set.of());
  }

  @AfterEach
  void endTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void withoutACandidateNoOfficerIsReadAndNothingIsWritten() {
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE)).thenReturn(List.of());

    assertThat(service(500).remindDue()).isZero();

    verifyNoInteractions(officers, outbox);
  }

  @Test
  void eachCandidateGetsOneRowPerOfficerInATransactionOfItsOwnAfterTheTimeouts() {
    ReminderCandidate first = candidate(1);
    ReminderCandidate second = candidate(2);
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE))
        .thenReturn(List.of(first, second));
    when(transfers.findAwaitingReminder(second, TWELVE_HOURS, PAGE)).thenReturn(List.of());
    stillHeld(first, second);

    assertThat(service(500).remindDue()).isEqualTo(2);

    InOrder order = inOrder(timeouts, transfers, officers, outbox, transactions);
    // The page's reads: one transaction, the timeouts first.
    order.verify(transactions).getTransaction(any());
    order.verify(timeouts).apply();
    order.verify(transfers).findAwaitingReminder(null, TWELVE_HOURS, PAGE);
    order.verify(officers).activeComplianceOfficers();
    order.verify(outbox).recorded(anyCollection());
    order.verify(transactions).commit(any());
    for (ReminderCandidate candidate : List.of(first, second)) {
      order.verify(transactions).getTransaction(any());
      order.verify(timeouts).apply();
      order.verify(transfers).findAwaitingReminder(candidate.id(), TWELVE_HOURS);
      for (UUID officer : List.of(officerA, officerB)) {
        order
            .verify(outbox)
            .enqueueOnce(
                ReviewReminderEventIds.of(candidate.id(), officer),
                officer,
                EmailKind.TRANSFER_REVIEW_EXPIRING,
                values(candidate));
      }
      order.verify(transactions).commit(any());
    }
    order.verify(transfers).findAwaitingReminder(second, TWELVE_HOURS, PAGE);
    // The officers are read once per run.
    verify(officers, times(1)).activeComplianceOfficers();
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions, times(4)).getTransaction(definition.capture());
    assertThat(definition.getAllValues())
        .allSatisfy(
            d ->
                assertThat(d.getIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED));
    // The page reads are read-only; the per-transfer writes are not.
    assertThat(definition.getAllValues())
        .extracting(TransactionDefinition::isReadOnly)
        .containsExactly(true, false, false, true);
  }

  /** Nothing to write, but the run re-read held transfers: the counts are still logged. */
  @Test
  void aRunThatOnlyReReadsRemindedTransfersLogsItsCounts(CapturedOutput output) {
    ReminderCandidate done = candidate(1);
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE)).thenReturn(List.of(done));
    when(transfers.findAwaitingReminder(done, TWELVE_HOURS, PAGE)).thenReturn(List.of());
    when(outbox.recorded(anyCollection()))
        .thenReturn(
            Set.of(
                ReviewReminderEventIds.of(done.id(), officerA),
                ReviewReminderEventIds.of(done.id(), officerB)));

    assertThat(service(500).remindDue()).isZero();

    assertThat(output)
        .contains(
            "reminded=0, emails=0, alreadyReminded=1, skipped=0, failed=0, limitReached=false,"
                + " durationMs=");
  }

  /** The recorded check asks for every (transfer, officer) id of the page in one read. */
  @Test
  @SuppressWarnings("unchecked")
  void remindersAlreadyRecordedAreSkippedWithoutATransaction() {
    ReminderCandidate done = candidate(1);
    ReminderCandidate half = candidate(2);
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE)).thenReturn(List.of(done, half));
    when(transfers.findAwaitingReminder(half, TWELVE_HOURS, PAGE)).thenReturn(List.of());
    when(outbox.recorded(anyCollection()))
        .thenReturn(
            Set.of(
                ReviewReminderEventIds.of(done.id(), officerA),
                ReviewReminderEventIds.of(done.id(), officerB),
                ReviewReminderEventIds.of(half.id(), officerA)));
    stillHeld(half);

    assertThat(service(500).remindDue()).isEqualTo(1);

    ArgumentCaptor<java.util.Collection<UUID>> asked =
        ArgumentCaptor.forClass(java.util.Collection.class);
    verify(outbox).recorded(asked.capture());
    assertThat(asked.getValue())
        .containsExactlyInAnyOrder(
            ReviewReminderEventIds.of(done.id(), officerA),
            ReviewReminderEventIds.of(done.id(), officerB),
            ReviewReminderEventIds.of(half.id(), officerA),
            ReviewReminderEventIds.of(half.id(), officerB));
    verify(transfers, never()).findAwaitingReminder(done.id(), TWELVE_HOURS);
    verify(outbox, times(1)).enqueueOnce(any(), any(), any(), any());
    verify(outbox)
        .enqueueOnce(
            ReviewReminderEventIds.of(half.id(), officerB),
            officerB,
            EmailKind.TRANSFER_REVIEW_EXPIRING,
            values(half));
  }

  /** Decided, cancelled or expired since the page was read: nothing is written. */
  @Test
  void aTransferNoLongerACandidateWhenReadAgainGetsNothing() {
    ReminderCandidate decided = candidate(1);
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE)).thenReturn(List.of(decided));
    when(transfers.findAwaitingReminder(decided, TWELVE_HOURS, PAGE)).thenReturn(List.of());
    when(transfers.findAwaitingReminder(decided.id(), TWELVE_HOURS)).thenReturn(Optional.empty());

    assertThat(service(500).remindDue()).isZero();

    verify(outbox, never()).enqueueOnce(any(), any(), any(), any());
    verify(transactions, never()).rollback(any());
  }

  /** Another run or instance wrote them first: no error, and the transfer does not count. */
  @Test
  void rowsAnotherRunWroteFirstAreNotCounted() {
    ReminderCandidate raced = candidate(1);
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE)).thenReturn(List.of(raced));
    when(transfers.findAwaitingReminder(raced, TWELVE_HOURS, PAGE)).thenReturn(List.of());
    when(transfers.findAwaitingReminder(raced.id(), TWELVE_HOURS)).thenReturn(Optional.of(raced));
    when(outbox.enqueueOnce(any(), any(), any(), any())).thenReturn(false);

    assertThat(service(500).remindDue()).isZero();

    verify(outbox, times(2)).enqueueOnce(any(), any(), any(), any());
    verify(transactions, never()).rollback(any());
  }

  @Test
  void aFailingTransferRollsBackAloneIsLoggedByIdAndClassAndTheRunGoesOn(CapturedOutput output) {
    ReminderCandidate failing = candidate(1);
    ReminderCandidate next = candidate(2);
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE))
        .thenReturn(List.of(failing, next));
    when(transfers.findAwaitingReminder(next, TWELVE_HOURS, PAGE)).thenReturn(List.of());
    stillHeld(failing, next);
    when(outbox.enqueueOnce(
            eq(ReviewReminderEventIds.of(failing.id(), officerB)), any(), any(), any()))
        .thenThrow(new IllegalStateException("secret detail 4f2a"));

    assertThat(service(500).remindDue()).isEqualTo(1);

    verify(transactions, times(1)).rollback(any());
    verify(outbox)
        .enqueueOnce(
            ReviewReminderEventIds.of(next.id(), officerB),
            officerB,
            EmailKind.TRANSFER_REVIEW_EXPIRING,
            values(next));
    assertThat(output)
        .contains("TRANSFER_REVIEW_REMINDER_FAILED")
        .contains("transferId=" + failing.id())
        .contains("failure=IllegalStateException")
        .contains(
            "reminded=1, emails=2, alreadyReminded=0, skipped=0, failed=1, limitReached=false, durationMs=")
        .doesNotContain("secret detail 4f2a")
        .doesNotContain("TRF-");
  }

  /** Only transfers that got new reminders count; the run stops once the maximum is reached. */
  @Test
  void theRunStopsAtTheMaximumAndOnlyNewRemindersCount(CapturedOutput output) {
    ReminderCandidate recorded = candidate(1);
    ReminderCandidate failing = candidate(2);
    ReminderCandidate first = candidate(3);
    ReminderCandidate second = candidate(4);
    ReminderCandidate third = candidate(5);
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE))
        .thenReturn(List.of(recorded, failing, first));
    when(transfers.findAwaitingReminder(first, TWELVE_HOURS, PAGE))
        .thenReturn(List.of(second, third));
    when(outbox.recorded(anyCollection()))
        .thenReturn(
            Set.of(
                ReviewReminderEventIds.of(recorded.id(), officerA),
                ReviewReminderEventIds.of(recorded.id(), officerB)));
    stillHeld(failing, first, second, third);
    when(transfers.findAwaitingReminder(failing.id(), TWELVE_HOURS))
        .thenThrow(new DataAccessResourceFailureException("down"));

    assertThat(service(2).remindDue()).isEqualTo(2);

    verify(transfers, never()).findAwaitingReminder(third.id(), TWELVE_HOURS);
    verify(transfers, never()).findAwaitingReminder(second, TWELVE_HOURS, PAGE);
    assertThat(output)
        .contains(
            "reminded=2, emails=4, alreadyReminded=1, skipped=0, failed=1, limitReached=true, durationMs=");
  }

  /** Decision 5: an alertable WARN with a fixed key and no transfer data. */
  @Test
  void withNoActiveOfficerTheRunWarnsUnderItsAlertKeyAndWritesNothing(CapturedOutput output) {
    ReminderCandidate due = candidate(1);
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE)).thenReturn(List.of(due));
    when(officers.activeComplianceOfficers()).thenReturn(List.of());

    assertThat(service(500).remindDue()).isZero();

    verify(outbox, never()).recorded(anyCollection());
    verify(outbox, never()).enqueueOnce(any(), any(), any(), any());
    assertThat(TransferReviewReminderService.NO_OFFICER_LOG)
        .startsWith("TRANSFER_REVIEW_REMINDER_NO_OFFICER: ");
    assertThat(TransferReviewReminderService.FAILURE_LOG)
        .startsWith("TRANSFER_REVIEW_REMINDER_FAILED: ");
    assertThat(
            output.getOut().lines().filter(l -> l.contains("TRANSFER_REVIEW_REMINDER_NO_OFFICER")))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .contains("WARN")
                    .doesNotContain(due.id().toString())
                    .doesNotContain(due.reference()));
  }

  @Test
  void aFailingReadOfTheRunEndsItEarly(CapturedOutput output) {
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE))
        .thenThrow(new DataAccessResourceFailureException("secret 9c1d"));

    assertThat(service(500).remindDue()).isZero();

    verifyNoInteractions(outbox);
    assertThat(output)
        .contains(
            "Transfer review reminder run stopped: failure=DataAccessResourceFailureException")
        .doesNotContain("secret 9c1d");
  }

  /**
   * D14: the officer read runs in the page's transaction after the timeouts; a lock timeout there
   * rolls it back and ends the run, logged by class only.
   */
  @Test
  void aLockTimeoutOnTheOfficerReadEndsTheRunCleanly(CapturedOutput output) {
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE))
        .thenReturn(List.of(candidate(1)));
    when(officers.activeComplianceOfficers())
        .thenThrow(new CannotAcquireLockException("lock timeout on auth.users 7e2b"));

    assertThat(service(500).remindDue()).isZero();

    InOrder order = inOrder(transactions, timeouts, officers);
    order.verify(transactions).getTransaction(any());
    order.verify(timeouts).apply();
    order.verify(officers).activeComplianceOfficers();
    order.verify(transactions).rollback(any());
    verifyNoInteractions(outbox);
    verify(transfers, never()).findAwaitingReminder(any(UUID.class), anyLong());
    assertThat(output)
        .contains("Transfer review reminder run stopped: failure=CannotAcquireLockException")
        .doesNotContain("7e2b");
  }

  /** As above, for the read of the reminders already recorded. */
  @Test
  void aLockTimeoutOnTheRecordedReadEndsTheRunCleanly(CapturedOutput output) {
    when(transfers.findAwaitingReminder(null, TWELVE_HOURS, PAGE))
        .thenReturn(List.of(candidate(1)));
    when(outbox.recorded(anyCollection()))
        .thenThrow(new QueryTimeoutException("statement timeout 3a9f"));

    assertThat(service(500).remindDue()).isZero();

    InOrder order = inOrder(transactions, timeouts, outbox);
    order.verify(transactions).getTransaction(any());
    order.verify(timeouts).apply();
    order.verify(outbox).recorded(anyCollection());
    order.verify(transactions).rollback(any());
    verify(outbox, never()).enqueueOnce(any(), any(), any(), any());
    assertThat(output)
        .contains("Transfer review reminder run stopped: failure=QueryTimeoutException")
        .doesNotContain("3a9f");
  }

  @Test
  void itRefusesToRunInsideACallersTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> service(500).remindDue())
        .isInstanceOf(IllegalTransactionStateException.class);
    verifyNoInteractions(transfers, officers, outbox);
  }
}
