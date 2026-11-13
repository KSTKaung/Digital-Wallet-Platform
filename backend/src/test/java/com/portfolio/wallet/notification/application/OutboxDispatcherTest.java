package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.time.BusinessTimeProperties;
import com.portfolio.wallet.notification.persistence.NotificationRepository;
import com.portfolio.wallet.notification.persistence.OutboxRow;
import com.portfolio.wallet.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The outbox dispatcher's policy (documentations/06 MVP-ADR-25; owner, 2026-11-04, N1, R1) against
 * a mocked table. A claimed row carries its attempt count after the claim: the claim counted
 * itself, and that count is the fencing token of every result.
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxDispatcherTest {

  private static final String ADDRESS = "customer@example.test";
  private static final Instant OCCURRED = Instant.parse("2026-11-03T07:05:09Z");
  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final int MAX = 5;
  private static final Duration LOOKUP = Duration.ofSeconds(5);
  private static final String LONG_NAME =
      "AVeryLongExceptionNameThatGoesOnAndOnAndOnWellBeyondTheOneHundredCharactersTheColumnHoldsSoItMustBeCut";

  private final NotificationRepository notifications = mock(NotificationRepository.class);
  private final RecipientAddresses recipients = mock(RecipientAddresses.class);
  private final RecordingEmailSender sender = new RecordingEmailSender();
  private final MutableClock clock = new MutableClock();
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final OutboxProperties properties =
      new OutboxProperties(
          MAX,
          List.of(
              Duration.ofMinutes(1),
              Duration.ofMinutes(5),
              Duration.ofMinutes(15),
              Duration.ofMinutes(60)),
          3,
          Duration.ofSeconds(10),
          LEASE,
          Duration.ofSeconds(30),
          LOOKUP);
  private final OutboxDispatcher dispatcher =
      new OutboxDispatcher(
          notifications,
          recipients,
          sender,
          properties,
          new BusinessTimeProperties(ZoneId.of("Asia/Bangkok")),
          clock,
          transactions);

  private final UUID user = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    when(notifications.markSent(any(), anyInt())).thenReturn(1);
    when(notifications.scheduleRetry(any(), anyInt(), any(), anyString())).thenReturn(1);
    when(notifications.markFailed(any(), anyInt(), anyString())).thenReturn(1);
    when(notifications.failExpiredLastClaims(any(), anyInt())).thenReturn(List.of());
    when(recipients.emailOf(user, LOOKUP)).thenReturn(Optional.of(ADDRESS));
  }

  /** A row as the claim returns it: {@code claim} is its attempt count after the claim. */
  private OutboxRow claimed(int claim) {
    return claimed(claim, "WALLET_FROZEN");
  }

  private OutboxRow claimed(int claim, String kind) {
    return new OutboxRow(UUID.randomUUID(), user, kind, claim, OCCURRED, Map.of());
  }

  private void claimable(OutboxRow row) {
    when(notifications.claimDue(row.eventId(), clock.instant(), clock.instant().plus(LEASE), MAX))
        .thenReturn(Optional.of(row));
  }

  @Test
  void aClaimedRowIsSentWithTheTimeOfTheChangeAndMarkedSentUnderItsClaim() {
    OutboxRow row = claimed(1);
    claimable(row);

    assertThat(dispatcher.dispatch(row.eventId())).isTrue();

    assertThat(sender.messages()).hasSize(1);
    EmailMessage message = sender.messages().getFirst();
    assertThat(message.kind()).isEqualTo(EmailKind.WALLET_FROZEN);
    assertThat(message.to()).isEqualTo(ADDRESS);
    // 07:05:09 UTC is 14:05:09 in Bangkok (K2).
    assertThat(message.body()).contains("3 November 2026 at 14:05:09 (Asia/Bangkok time)");
    InOrder order = inOrder(notifications, transactions);
    order
        .verify(notifications)
        .claimDue(row.eventId(), clock.instant(), clock.instant().plus(LEASE), MAX);
    order.verify(transactions).commit(any());
    order.verify(notifications).markSent(row.eventId(), 1);
    order.verify(transactions).commit(any());
  }

  /** The claim never goes past the maximum: the dispatcher passes it to every claim (R1). */
  @Test
  void everyClaimIsBoundedByTheMaximum() {
    when(notifications.claimNextDue(any(), any(), anyInt())).thenReturn(Optional.empty());

    dispatcher.dispatchDue();
    dispatcher.dispatch(UUID.randomUUID());

    verify(notifications).claimNextDue(any(), any(), eq(MAX));
    verify(notifications).claimDue(any(), any(), any(), eq(MAX));
  }

  @Test
  void aRowThatIsNotClaimableIsNotSent() {
    assertThat(dispatcher.dispatch(UUID.randomUUID())).isFalse();

    assertThat(sender.messages()).isEmpty();
    verify(notifications, never()).markSent(any(), anyInt());
  }

  /** A failure records the next attempt time only; the attempt count is the claim's (R1). */
  @Test
  void anSmtpFailureSchedulesTheNextAttemptByTheBackoff(CapturedOutput output) {
    OutboxRow row = claimed(1);
    claimable(row);
    sender.setFailing(true);

    dispatcher.dispatch(row.eventId());

    verify(notifications)
        .scheduleRetry(
            row.eventId(), 1, clock.instant().plus(Duration.ofMinutes(1)), "IllegalStateException");
    verify(notifications, never()).markSent(any(), anyInt());
    verify(notifications, never()).markFailed(any(), anyInt(), anyString());
    // RecordingEmailSender's failure message quotes the address; only the class is logged.
    assertThat(output.getOut())
        .contains("will retry")
        .contains(row.eventId().toString())
        .doesNotContain(ADDRESS);
  }

  @Test
  void eachFailedClaimWaitsItsBackoffEntry() {
    for (int claim = 1; claim < MAX; claim++) {
      OutboxRow row = claimed(claim);
      claimable(row);
      sender.setFailing(true);

      dispatcher.dispatch(row.eventId());

      verify(notifications)
          .scheduleRetry(
              eq(row.eventId()),
              eq(claim),
              eq(clock.instant().plus(properties.waitAfter(claim))),
              anyString());
    }
  }

  /** The fifth claim stays PENDING while it sends; its success makes the row SENT (R1). */
  @Test
  void theLastClaimMaySucceed() {
    OutboxRow row = claimed(MAX);
    claimable(row);

    dispatcher.dispatch(row.eventId());

    verify(notifications).markSent(row.eventId(), MAX);
    verify(notifications, never()).markFailed(any(), anyInt(), anyString());
  }

  /** The fifth claim's failure makes the row FAILED for good (R1). */
  @Test
  void theLastClaimsFailureMarksTheRowFailed(CapturedOutput output) {
    OutboxRow row = claimed(MAX);
    claimable(row);
    sender.setFailing(true);

    dispatcher.dispatch(row.eventId());

    verify(notifications).markFailed(row.eventId(), MAX, "IllegalStateException");
    verify(notifications, never()).scheduleRetry(any(), anyInt(), any(), anyString());
    assertThat(output.getOut())
        .contains("failed for good")
        .contains(row.eventId().toString())
        .contains("WALLET_FROZEN")
        .doesNotContain(ADDRESS);
  }

  /** Each run starts by failing the last claims whose lease ended without a result (R1). */
  @Test
  void aRunFirstFailsExpiredLastClaims(CapturedOutput output) {
    OutboxRow expired = claimed(MAX);
    when(notifications.failExpiredLastClaims(clock.instant(), MAX)).thenReturn(List.of(expired));
    when(notifications.claimNextDue(any(), any(), anyInt())).thenReturn(Optional.empty());

    dispatcher.dispatchDue();

    InOrder order = inOrder(notifications);
    order.verify(notifications).failExpiredLastClaims(clock.instant(), MAX);
    order.verify(notifications).claimNextDue(any(), any(), anyInt());
    assertThat(output.getOut())
        .contains("lease ended without a result, failed for good")
        .contains(expired.eventId().toString())
        .doesNotContain(user.toString());
  }

  /** A result whose fenced update finds a newer claim is ignored and logged (R1). */
  @Test
  void aResultFromAnOlderClaimIsIgnored(CapturedOutput output) {
    OutboxRow stale = claimed(4);
    claimable(stale);
    sender.setFailing(true);
    when(notifications.scheduleRetry(eq(stale.eventId()), eq(4), any(), anyString())).thenReturn(0);

    assertThat(dispatcher.dispatch(stale.eventId())).isTrue();

    assertThat(output.getOut())
        .contains("result from an older claim ignored")
        .contains(stale.eventId().toString());
  }

  @Test
  void aStaleSuccessOrFailureIsIgnoredToo(CapturedOutput output) {
    OutboxRow sent = claimed(2);
    claimable(sent);
    when(notifications.markSent(sent.eventId(), 2)).thenReturn(0);
    dispatcher.dispatch(sent.eventId());

    OutboxRow last = claimed(MAX);
    claimable(last);
    sender.setFailing(true);
    when(notifications.markFailed(eq(last.eventId()), eq(MAX), anyString())).thenReturn(0);
    dispatcher.dispatch(last.eventId());

    assertThat(output.getOut().split("result from an older claim ignored", -1)).hasSize(3);
  }

  @Test
  void aMissingAddressFollowsTheSameRetryPolicy() {
    OutboxRow row = claimed(1);
    claimable(row);
    when(recipients.emailOf(user, LOOKUP)).thenReturn(Optional.empty());

    dispatcher.dispatch(row.eventId());

    verify(notifications)
        .scheduleRetry(
            row.eventId(),
            1,
            clock.instant().plus(Duration.ofMinutes(1)),
            OutboxDispatcher.NO_ADDRESS);
    assertThat(sender.messages()).isEmpty();
  }

  /** A database error in the lookup runs outside any transaction and fails the attempt (N1). */
  @Test
  void aDatabaseErrorInTheLookupFailsTheAttempt() {
    OutboxRow row = claimed(MAX);
    claimable(row);
    when(recipients.emailOf(user, LOOKUP)).thenThrow(new DataAccessResourceFailureException("db"));

    dispatcher.dispatch(row.eventId());

    verify(notifications).markFailed(row.eventId(), MAX, "DataAccessResourceFailureException");
    assertThat(sender.messages()).isEmpty();
  }

  /**
   * S1: the lookup runs with the configured statement timeout, and a timeout fails the attempt like
   * any other lookup error.
   */
  @Test
  void aLookupTimeoutFailsTheAttempt() {
    OutboxRow row = claimed(1);
    claimable(row);
    when(recipients.emailOf(user, LOOKUP))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("canceled"));

    dispatcher.dispatch(row.eventId());

    verify(recipients).emailOf(user, properties.addressLookupTimeout());
    verify(notifications)
        .scheduleRetry(
            row.eventId(), 1, clock.instant().plus(Duration.ofMinutes(1)), "QueryTimeoutException");
    assertThat(sender.messages()).isEmpty();
  }

  /** An unknown stored kind fails the attempt and is retried, then fails for good (N1). */
  @Test
  void anUnknownKindFailsTheAttempt() {
    OutboxRow first = claimed(1, "SOMETHING_NEW");
    claimable(first);
    dispatcher.dispatch(first.eventId());
    verify(notifications)
        .scheduleRetry(
            first.eventId(),
            1,
            clock.instant().plus(Duration.ofMinutes(1)),
            OutboxDispatcher.UNKNOWN_KIND);

    OutboxRow last = claimed(MAX, "SOMETHING_NEW");
    claimable(last);
    dispatcher.dispatch(last.eventId());
    verify(notifications).markFailed(last.eventId(), MAX, OutboxDispatcher.UNKNOWN_KIND);
    verify(recipients, never()).emailOf(any(), any());
  }

  /** The recorded failure is cut to the column size (varchar(100), V9). */
  @Test
  void anOverlongFailureNameIsCutToTheColumnSize() {
    OutboxRow row = claimed(1);
    claimable(row);
    class AVeryLongExceptionNameThatGoesOnAndOnAndOnWellBeyondTheOneHundredCharactersTheColumnHoldsSoItMustBeCut
        extends RuntimeException {}
    when(recipients.emailOf(user, LOOKUP))
        .thenThrow(
            new AVeryLongExceptionNameThatGoesOnAndOnAndOnWellBeyondTheOneHundredCharactersTheColumnHoldsSoItMustBeCut());

    dispatcher.dispatch(row.eventId());

    verify(notifications)
        .scheduleRetry(
            eq(row.eventId()),
            eq(1),
            any(),
            eq(LONG_NAME.substring(0, OutboxDispatcher.MAX_FAILURE_LENGTH)));
    assertThat(OutboxDispatcher.bounded("short")).isEqualTo("short");
  }

  /**
   * A failure while recording the result is logged and the run goes on; the row keeps its lease and
   * is claimed again after it (the documented duplicate window).
   */
  @Test
  void aFailureToRecordTheResultDoesNotStopTheRun(CapturedOutput output) {
    OutboxRow failing = claimed(1);
    OutboxRow next = claimed(1);
    when(notifications.claimNextDue(any(), any(), anyInt()))
        .thenReturn(Optional.of(failing))
        .thenReturn(Optional.of(next))
        .thenReturn(Optional.empty());
    when(notifications.markSent(failing.eventId(), 1))
        .thenThrow(new DataAccessResourceFailureException("db"));

    assertThat(dispatcher.dispatchDue()).isEqualTo(2);

    assertThat(sender.messages()).hasSize(2);
    verify(notifications).markSent(next.eventId(), 1);
    assertThat(output.getOut())
        .contains("result not recorded, the row is claimed again after its lease")
        .contains(failing.eventId().toString());
  }

  @Test
  void dispatchDueHandlesRowsUntilNoneIsDueAndStopsAtTheBatchSize() {
    when(notifications.claimNextDue(any(), any(), anyInt()))
        .thenReturn(Optional.of(claimed(1)))
        .thenReturn(Optional.of(claimed(1)))
        .thenReturn(Optional.empty());

    assertThat(dispatcher.dispatchDue()).isEqualTo(2);
    assertThat(sender.messages()).hasSize(2);

    when(notifications.claimNextDue(any(), any(), anyInt())).thenReturn(Optional.of(claimed(1)));
    assertThat(dispatcher.dispatchDue()).isEqualTo(properties.batchSize());
  }

  /** Q9: a money email is rendered with its stored values. */
  @Test
  void aMoneyEmailIsSentWithItsStoredValues() {
    OutboxRow row =
        new OutboxRow(
            UUID.randomUUID(),
            user,
            "TRANSFER_SENT",
            1,
            OCCURRED,
            Map.of("amount", "1300.00", "currency", "THB", "reference", "TRF-ABC123"));
    claimable(row);

    assertThat(dispatcher.dispatch(row.eventId())).isTrue();

    assertThat(sender.messages())
        .singleElement()
        .satisfies(message -> assertThat(message.body()).contains("1,300.00 THB", "TRF-ABC123"));
    verify(notifications).markSent(row.eventId(), 1);
  }

  /** Stored values that cannot be read, or that do not match the kind, fail only that attempt. */
  @Test
  void unreadableOrMismatchedValuesFailTheAttemptAndAreRetried() {
    OutboxRow unreadable =
        new OutboxRow(UUID.randomUUID(), user, "TRANSFER_SENT", 1, OCCURRED, null);
    OutboxRow mismatched = claimed(1, "TRANSFER_SENT");
    claimable(unreadable);
    claimable(mismatched);

    assertThat(dispatcher.dispatch(unreadable.eventId())).isTrue();
    assertThat(dispatcher.dispatch(mismatched.eventId())).isTrue();

    assertThat(sender.messages()).isEmpty();
    verify(notifications)
        .scheduleRetry(
            eq(unreadable.eventId()), eq(1), any(), eq(OutboxDispatcher.UNREADABLE_VALUES));
    verify(notifications)
        .scheduleRetry(eq(mismatched.eventId()), eq(1), any(), eq("IllegalArgumentException"));
  }

  /** Per-row failures do not stop the run: each row fails its attempt and the next is handled. */
  @Test
  void aRowThatFailsDoesNotHoldBackTheNextOne() {
    OutboxRow broken =
        new OutboxRow(UUID.randomUUID(), UUID.randomUUID(), "KYC_REJECTED", 1, OCCURRED, Map.of());
    OutboxRow good = claimed(1);
    when(recipients.emailOf(broken.userId(), LOOKUP))
        .thenThrow(new DataAccessResourceFailureException("db"));
    when(notifications.claimNextDue(any(), any(), anyInt()))
        .thenReturn(Optional.of(broken))
        .thenReturn(Optional.of(good))
        .thenReturn(Optional.empty());

    assertThat(dispatcher.dispatchDue()).isEqualTo(2);

    verify(notifications, times(1)).scheduleRetry(eq(broken.eventId()), eq(1), any(), anyString());
    verify(notifications).markSent(good.eventId(), 1);
  }

  @Test
  void aFailingClaimOrExpiryStepEndsTheRunWithoutThrowing(CapturedOutput output) {
    when(notifications.claimNextDue(any(), any(), anyInt()))
        .thenThrow(new DataAccessResourceFailureException("db down"));
    assertThat(dispatcher.dispatchDue()).isZero();

    when(notifications.failExpiredLastClaims(any(), anyInt()))
        .thenThrow(new DataAccessResourceFailureException("db down"));
    assertThat(dispatcher.dispatchDue()).isZero();

    assertThat(output.getOut().split("Email outbox run stopped", -1)).hasSize(3);
  }
}
