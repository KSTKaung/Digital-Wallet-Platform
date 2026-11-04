package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.portfolio.wallet.notification.persistence.NotificationRepository;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.support.MutableClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The email outbox against PostgreSQL (documentations/06 MVP-ADR-25; owner, 2026-11-04, N1, N2):
 * rows commit and roll back with the transaction that writes them; a claim leases a row so no other
 * run takes it; delivery is at least once, with the state transitions idempotent; any failure in
 * the send step counts as an attempt and never holds back the next row; retries follow the schedule
 * of the application clock until the row is {@code FAILED} after the fifth attempt. Every outbox
 * time is the application clock's, so the fixed clock drives everything. The scheduled job is off
 * in tests.
 */
@Import(NotificationOutboxIT.Beans.class)
@ExtendWith(OutputCaptureExtension.class)
class NotificationOutboxIT extends DatabaseIntegrationTest {

  private static final long TIMEOUT_SECONDS = 30;

  /** Users whose address lookup fails with a database error. */
  static final Set<UUID> FAILING_LOOKUPS = ConcurrentHashMap.newKeySet();

  @TestConfiguration
  static class Beans {
    @Bean
    @Primary
    MutableClock outboxTestClock() {
      return new MutableClock();
    }

    /** Slow enough that concurrent dispatchers really overlap while a row is leased. */
    @Bean
    @Primary
    RecordingEmailSender slowRecordingEmailSender() {
      return new RecordingEmailSender() {
        @Override
        public void send(EmailMessage message) {
          try {
            Thread.sleep(20);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          super.send(message);
        }
      };
    }

    /** The real lookup, except a database error for the users in {@link #FAILING_LOOKUPS}. */
    @Bean
    @Primary
    RecipientAddresses failingForSomeRecipientAddresses(
        @Qualifier("authRecipientAddresses") RecipientAddresses real) {
      return (userId, timeout) -> {
        if (FAILING_LOOKUPS.contains(userId)) {
          throw new DataAccessResourceFailureException("lookup failed");
        }
        return real.emailOf(userId, timeout);
      };
    }
  }

  @Autowired NotificationOutbox outbox;
  @Autowired OutboxDispatcher dispatcher;
  @Autowired OutboxProperties properties;
  @Autowired RecordingEmailSender mail;
  @Autowired MutableClock clock;
  @Autowired PlatformTransactionManager transactions;
  @Autowired RecipientAddresses recipients;
  @Autowired com.portfolio.wallet.common.time.BusinessTimeProperties businessTime;
  @MockitoSpyBean NotificationRepository repository;

  @BeforeEach
  void reset() {
    mail.clear();
    FAILING_LOOKUPS.clear();
  }

  /** A customer account, whose email the dispatcher looks up when it sends. */
  private Map<String, Object> account() throws Exception {
    Map<String, Object> user = validUser();
    insert("auth.users", user);
    return user;
  }

  private UUID enqueueCommitted(UUID userId, EmailKind kind) {
    return new TransactionTemplate(transactions).execute(status -> outbox.enqueue(userId, kind));
  }

  private Map<String, Object> row(UUID eventId) {
    return jdbc.queryForMap(
        "SELECT user_id, kind, status, attempts, payload::text AS payload, last_failure,"
            + " occurred_at, next_attempt_at, sent_at, row_to_json(n)::text AS everything"
            + " FROM notification.notifications n WHERE event_id = ?",
        eventId);
  }

  private Instant nextAttempt(UUID eventId) {
    return ((Timestamp) row(eventId).get("next_attempt_at")).toInstant();
  }

  private List<EmailMessage> mailTo(Object address) {
    return mail.messages().stream().filter(m -> m.to().equals(address)).toList();
  }

  private static <T> T spied(T bean) {
    return AopTestUtils.getUltimateTargetObject(bean);
  }

  // Written with the change -------------------------------------------------------------------

  @Test
  void aRowCommitsWithItsTransactionIsDueByTheApplicationClockAndHoldsNoAddress() throws Exception {
    Map<String, Object> user = account();
    UUID userId = (UUID) user.get("id");

    UUID eventId = enqueueCommitted(userId, EmailKind.WALLET_FROZEN);

    Map<String, Object> stored = row(eventId);
    assertThat(stored)
        .containsEntry("user_id", userId)
        .containsEntry("kind", "WALLET_FROZEN")
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("payload", "{}");
    // N2: due at the application clock's time of the enqueue.
    assertThat(nextAttempt(eventId)).isEqualTo(clock.instant());
    assertThat((String) stored.get("everything"))
        .doesNotContain((String) user.get("email"), "@", (String) user.get("phone_number"));
  }

  @Test
  void aRolledBackTransactionLeavesNoRowAndSendsNothing() throws Exception {
    Map<String, Object> user = account();
    UUID userId = (UUID) user.get("id");

    UUID eventId =
        new TransactionTemplate(transactions)
            .execute(
                status -> {
                  UUID id = outbox.enqueue(userId, EmailKind.KYC_REJECTED);
                  status.setRollbackOnly();
                  return id;
                });

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.notifications WHERE event_id = ?",
                Integer.class,
                eventId))
        .isZero();
    dispatcher.dispatchDue();
    assertThat(mailTo(user.get("email"))).isEmpty();
  }

  @Test
  void enqueueRefusesToRunOutsideATransaction() {
    assertThatThrownBy(() -> outbox.enqueue(UUID.randomUUID(), EmailKind.KYC_APPROVED))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  // Sent, at least once -------------------------------------------------------------------------

  @Test
  void aDueRowIsSentAndASecondDispatchSendsNothing() throws Exception {
    Map<String, Object> user = account();
    UUID eventId = enqueueCommitted((UUID) user.get("id"), EmailKind.KYC_APPROVED);

    assertThat(dispatcher.dispatch(eventId)).isTrue();
    assertThat(dispatcher.dispatch(eventId)).isFalse();
    clock.advance(Duration.ofDays(1));
    dispatcher.dispatchDue();

    assertThat(mailTo(user.get("email"))).hasSize(1);
    EmailMessage message = mailTo(user.get("email")).getFirst();
    assertThat(message.kind()).isEqualTo(EmailKind.KYC_APPROVED);
    // The email states the time of the change in the business zone (K2).
    Instant occurred = ((Timestamp) row(eventId).get("occurred_at")).toInstant();
    assertThat(message.body()).contains(OutboxTemplates.time(occurred, ZoneId.of("Asia/Bangkok")));
    assertThat(row(eventId)).containsEntry("status", "SENT").containsEntry("attempts", 1);
    assertThat(row(eventId).get("sent_at")).isNotNull();
  }

  /** The lease keeps a claimed row from every other claim until it ends (N1). */
  @Test
  void aLeasedRowCannotBeClaimedAgainUntilTheLeaseEnds() throws Exception {
    UUID eventId = enqueueCommitted((UUID) account().get("id"), EmailKind.WALLET_FROZEN);
    Instant now = clock.instant();

    Optional<?> first =
        new TransactionTemplate(transactions)
            .execute(
                s ->
                    repository.claimDue(
                        eventId, now, now.plus(properties.lease()), properties.maxAttempts()));
    assertThat(first).isPresent();
    assertThat(nextAttempt(eventId)).isEqualTo(now.plus(properties.lease()));

    assertThat(dispatcher.dispatch(eventId)).as("leased").isFalse();
    clock.advance(properties.lease().minusSeconds(1));
    assertThat(dispatcher.dispatch(eventId)).as("still leased").isFalse();
    clock.advance(Duration.ofSeconds(1));
    assertThat(dispatcher.dispatch(eventId)).as("the lease ended").isTrue();
    assertThat(row(eventId)).containsEntry("status", "SENT").containsEntry("attempts", 2);
  }

  /**
   * If recording SENT fails after the SMTP server accepted the message, the row keeps its lease and
   * is claimed again when it ends: the email is sent a second time, once (the documented
   * at-least-once duplicate, MVP-ADR-25).
   */
  @Test
  void aFailedSentMarkIsClaimedAgainAfterTheLeaseAndSentOnceMore() throws Exception {
    Map<String, Object> user = account();
    UUID eventId = enqueueCommitted((UUID) user.get("id"), EmailKind.WALLET_UNFROZEN);
    doThrow(new DataAccessResourceFailureException("forced"))
        .doCallRealMethod()
        .when(spied(repository))
        .markSent(any(), anyInt());

    assertThat(dispatcher.dispatch(eventId)).isTrue();
    assertThat(row(eventId)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
    assertThat(mailTo(user.get("email"))).hasSize(1);
    assertThat(dispatcher.dispatch(eventId)).as("still leased").isFalse();

    clock.advance(properties.lease());
    assertThat(dispatcher.dispatch(eventId)).isTrue();
    assertThat(dispatcher.dispatch(eventId)).isFalse();

    assertThat(row(eventId)).containsEntry("status", "SENT").containsEntry("attempts", 2);
    assertThat(mailTo(user.get("email"))).hasSize(2);
    doCallRealMethod().when(spied(repository)).markSent(any(), anyInt());
  }

  /** Concurrent dispatchers: the lease gives each row to one of them, and each is sent once. */
  @Test
  void concurrentDispatchersSendEachRowExactlyOnceWhenNothingFails() throws Exception {
    List<Object> addresses = new ArrayList<>();
    List<UUID> events = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      Map<String, Object> user = account();
      addresses.add(user.get("email"));
      events.add(enqueueCommitted((UUID) user.get("id"), EmailKind.WALLET_UNFROZEN));
    }

    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Integer>> runs = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        runs.add(
            () -> {
              int handled = 0;
              int last;
              do {
                last = dispatcher.dispatchDue();
                handled += last;
              } while (last > 0);
              return handled;
            });
      }
      for (Future<Integer> run : pool.invokeAll(runs)) {
        run.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    for (Object address : addresses) {
      assertThat(mailTo(address)).as("mail to %s", address).hasSize(1);
    }
    for (UUID event : events) {
      assertThat(row(event)).containsEntry("status", "SENT").containsEntry("attempts", 1);
    }
  }

  // Failures and retries ------------------------------------------------------------------------

  /**
   * A database error in the address lookup runs with no transaction open, counts as an attempt, and
   * does not hold back the next row (N1).
   */
  @Test
  void aDatabaseErrorInTheLookupCountsAnAttemptAndTheNextRowIsStillSent() throws Exception {
    UUID failingUser = (UUID) account().get("id");
    FAILING_LOOKUPS.add(failingUser);
    UUID failing = enqueueCommitted(failingUser, EmailKind.WALLET_FROZEN);
    clock.advance(Duration.ofMillis(1));
    Map<String, Object> other = account();
    UUID next = enqueueCommitted((UUID) other.get("id"), EmailKind.WALLET_FROZEN);

    deliverAll();

    assertThat(row(failing))
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 1)
        .containsEntry("last_failure", "DataAccessResourceFailureException");
    assertThat(nextAttempt(failing)).isEqualTo(clock.instant().plus(properties.waitAfter(1)));
    assertThat(row(next)).containsEntry("status", "SENT");
    assertThat(mailTo(other.get("email"))).hasSize(1);
  }

  /** An SMTP failure is retried after 1, 5, 15 and 60 minutes, then the row is FAILED for good. */
  @Test
  void aFailingSendIsRetriedOnTheScheduleAndFailsForGoodAfterTheFifthAttempt() throws Exception {
    Map<String, Object> user = account();
    UUID eventId = enqueueCommitted((UUID) user.get("id"), EmailKind.WALLET_FROZEN);
    mail.setFailing(true);
    try {
      assertThat(dispatcher.dispatch(eventId)).isTrue();
      assertThat(row(eventId)).containsEntry("attempts", 1).containsEntry("status", "PENDING");
      for (int failed = 1; failed < properties.maxAttempts(); failed++) {
        Duration wait = properties.waitAfter(failed);
        assertThat(nextAttempt(eventId)).isEqualTo(clock.instant().plus(wait));
        clock.advance(wait.minusSeconds(1));
        assertThat(dispatcher.dispatch(eventId)).as("not due before its time").isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(dispatcher.dispatch(eventId)).isTrue();
        assertThat(row(eventId)).containsEntry("attempts", failed + 1);
      }
    } finally {
      mail.setFailing(false);
    }

    assertThat(row(eventId))
        .containsEntry("status", "FAILED")
        .containsEntry("attempts", 5)
        .containsEntry("last_failure", "IllegalStateException")
        .containsEntry("sent_at", null);
    // Not retried automatically, also once the sender works again.
    clock.advance(Duration.ofDays(7));
    assertThat(dispatcher.dispatch(eventId)).isFalse();
    dispatcher.dispatchDue();
    assertThat(mailTo(user.get("email"))).isEmpty();
  }

  /** A failed address lookup follows the same policy (MVP-ADR-25). */
  @Test
  void aMissingAddressIsRetriedOnTheSameScheduleAndThenFails() {
    UUID eventId = enqueueCommitted(UUID.randomUUID(), EmailKind.KYC_REJECTED);

    for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
      assertThat(dispatcher.dispatch(eventId)).isTrue();
      if (attempt < properties.maxAttempts()) {
        clock.advance(properties.waitAfter(attempt));
      }
    }

    assertThat(row(eventId))
        .containsEntry("status", "FAILED")
        .containsEntry("attempts", 5)
        .containsEntry("last_failure", OutboxDispatcher.NO_ADDRESS);
  }

  // Claims are counted and fenced (owner, 2026-11-04, R1) -------------------------------------

  private void setAttempts(UUID eventId, int attempts) {
    jdbc.update(
        "UPDATE notification.notifications SET attempts = ? WHERE event_id = ?", attempts, eventId);
  }

  /** A claim with no result, as a crash after the claim leaves it. */
  private Optional<?> claimWithoutResult(UUID eventId) {
    Instant now = clock.instant();
    return new TransactionTemplate(transactions)
        .execute(
            s ->
                repository.claimDue(
                    eventId, now, now.plus(properties.lease()), properties.maxAttempts()));
  }

  /**
   * The security review's interleaving: worker A's 4th attempt blocks in a failing send past its
   * lease; B claims the row again (the 5th attempt) and delivers it; when A finally records its
   * failure, the result belongs to an older claim and is ignored, so the row stays SENT.
   */
  @Test
  void aStaleWorkersResultIsIgnoredAfterTheRowWasClaimedAgain(CapturedOutput output)
      throws Exception {
    Map<String, Object> user = account();
    UUID eventId = enqueueCommitted((UUID) user.get("id"), EmailKind.WALLET_FROZEN);
    setAttempts(eventId, 3);
    CountDownLatch sending = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    EmailSender blockedAndFailing =
        message -> {
          sending.countDown();
          try {
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          throw new IllegalStateException("smtp timed out");
        };
    OutboxDispatcher workerA =
        new OutboxDispatcher(
            repository,
            recipients,
            blockedAndFailing,
            properties,
            businessTime,
            clock,
            transactions);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> a = pool.submit(() -> workerA.dispatch(eventId));
      assertThat(sending.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(row(eventId)).containsEntry("attempts", 4);

      clock.advance(properties.lease());
      assertThat(dispatcher.dispatch(eventId)).as("worker B claims again").isTrue();
      assertThat(row(eventId)).containsEntry("status", "SENT").containsEntry("attempts", 5);

      release.countDown();
      assertThat(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    assertThat(row(eventId))
        .containsEntry("status", "SENT")
        .containsEntry("attempts", 5)
        .containsEntry("last_failure", null);
    assertThat(mailTo(user.get("email"))).hasSize(1);
    assertThat(output.getOut()).contains("result from an older claim ignored");
  }

  @Test
  void theFifthClaimMaySucceed() throws Exception {
    Map<String, Object> user = account();
    UUID eventId = enqueueCommitted((UUID) user.get("id"), EmailKind.KYC_APPROVED);
    setAttempts(eventId, 4);

    assertThat(dispatcher.dispatch(eventId)).isTrue();

    assertThat(row(eventId)).containsEntry("status", "SENT").containsEntry("attempts", 5);
    assertThat(mailTo(user.get("email"))).hasSize(1);
  }

  @Test
  void theFifthClaimsFailureMarksTheRowFailed() throws Exception {
    Map<String, Object> user = account();
    UUID eventId = enqueueCommitted((UUID) user.get("id"), EmailKind.KYC_APPROVED);
    setAttempts(eventId, 4);
    mail.setFailing(true);
    try {
      assertThat(dispatcher.dispatch(eventId)).isTrue();
    } finally {
      mail.setFailing(false);
    }

    assertThat(row(eventId))
        .containsEntry("status", "FAILED")
        .containsEntry("attempts", 5)
        .containsEntry("last_failure", "IllegalStateException");
  }

  /** The fifth claim's lease ends without a result: FAILED, and there is no sixth claim. */
  @Test
  void theFifthClaimsExpiredLeaseMarksTheRowFailedWithoutASixthClaim(CapturedOutput output)
      throws Exception {
    Map<String, Object> user = account();
    UUID eventId = enqueueCommitted((UUID) user.get("id"), EmailKind.KYC_APPROVED);
    setAttempts(eventId, 4);
    assertThat(claimWithoutResult(eventId)).isPresent();
    assertThat(row(eventId)).containsEntry("status", "PENDING").containsEntry("attempts", 5);

    clock.advance(properties.lease().minusSeconds(1));
    assertThat(dispatcher.dispatch(eventId)).as("still in progress").isFalse();
    assertThat(row(eventId)).containsEntry("status", "PENDING");
    clock.advance(Duration.ofSeconds(1));
    assertThat(dispatcher.dispatch(eventId)).as("no sixth claim").isFalse();

    assertThat(row(eventId))
        .containsEntry("status", "FAILED")
        .containsEntry("attempts", 5)
        .containsEntry("last_failure", "LeaseExpired");
    assertThat(mailTo(user.get("email"))).isEmpty();
    assertThat(output.getOut())
        .contains("lease ended without a result, failed for good")
        .contains(eventId.toString());
  }

  /** A row whose every claim crashes ends FAILED after five claims instead of looping forever. */
  @Test
  void aCrashLoopEndsFailedAfterFiveClaims() throws Exception {
    UUID eventId = enqueueCommitted((UUID) account().get("id"), EmailKind.WALLET_UNFROZEN);

    for (int claim = 1; claim <= properties.maxAttempts(); claim++) {
      assertThat(claimWithoutResult(eventId)).as("claim %d", claim).isPresent();
      assertThat(row(eventId)).containsEntry("attempts", claim);
      clock.advance(properties.lease());
    }
    assertThat(claimWithoutResult(eventId)).as("a sixth claim").isEmpty();
    dispatcher.dispatchDue();

    assertThat(row(eventId))
        .containsEntry("status", "FAILED")
        .containsEntry("attempts", 5)
        .containsEntry("last_failure", "LeaseExpired");
  }

  // Runs the dispatcher until no row is due at the current clock.
  private void deliverAll() {
    while (dispatcher.dispatchDue() > 0) {
      // until no row is due
    }
  }
}
