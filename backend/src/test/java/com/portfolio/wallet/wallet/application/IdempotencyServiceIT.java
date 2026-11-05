package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.support.MutableClock;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.RequestFingerprint;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link IdempotencyService} and {@link IdempotencyPurgeService} against PostgreSQL as {@code
 * wallet_app} (documentations/02 §1.4; doc 06 MVP-ADR-03, -06, -08; doc 07 §7, §8, D04, D05; doc 08
 * §4B.2 owner requirement 2 and AC 8). Deposits and withdrawals arrive in step 8, so the "movement"
 * here is a stand-in: it inserts a marker row ({@code wallet.lookup_attempts}) in the same
 * transaction, which shows whether its work committed or rolled back with the record.
 *
 * <p>The services are built over the context's repository and transaction manager with a {@link
 * MutableClock}, so expiry can be tested without a separate Spring context.
 */
class IdempotencyServiceIT extends DatabaseIntegrationTest {

  private static final Duration TTL = Duration.ofHours(24);
  private static final long TIMEOUT_SECONDS = 30;

  @Autowired PlatformTransactionManager transactionManager;
  @Autowired IdempotencyService contextService;
  @Autowired IdempotencyPurgeService contextPurge;

  private final MutableClock clock = new MutableClock();
  private IdempotencyRecordRepository records;
  private IdempotencyService service;
  private IdempotencyPurgeService purge;
  private ExecutorService pool;
  private UUID customer;

  @BeforeEach
  void setUp() {
    records = new IdempotencyRecordRepository(JdbcClient.create(dataSource));
    IdempotencyProperties properties =
        new IdempotencyProperties(
            TTL, new IdempotencyProperties.Purge(false, Duration.ofHours(1), 2));
    service = new IdempotencyService(records, properties, clock, transactionManager);
    purge = new IdempotencyPurgeService(records, properties, clock);
    pool = Executors.newCachedThreadPool();
    customer = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  // Fixtures ------------------------------------------------------------------------------------

  private IdempotentRequest deposit(UUID customerId, UUID key, String amount) {
    return new IdempotentRequest(
        key,
        RequestFingerprint.builder(
                Operation.DEPOSIT, customerId, Money.of(new BigDecimal(amount), Money.THB))
            .field("reference", "rent")
            .build());
  }

  /** Inserts the marker in the caller's transaction and returns a 201 naming it. */
  private StoredResponse moveMoney(UUID marker) {
    jdbc.update(
        "INSERT INTO wallet.lookup_attempts (user_id, client_ip) VALUES (?, '127.0.0.1'::inet)",
        marker);
    return new StoredResponse(201, "{\"id\": \"" + marker + "\", \"amount\": 100.00}");
  }

  private Supplier<StoredResponse> movement(UUID marker, AtomicInteger runs) {
    return () -> {
      runs.incrementAndGet();
      return moveMoney(marker);
    };
  }

  private Supplier<StoredResponse> rejectingAfterWork(UUID marker, ErrorCode code) {
    return () -> {
      moveMoney(marker);
      throw new ApiException(code, "Rejected for test");
    };
  }

  private boolean markerCommitted(UUID marker) {
    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM wallet.lookup_attempts WHERE user_id = ?)",
        Boolean.class,
        marker);
  }

  private int recordCount(UUID customerId, UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM wallet.idempotency_records"
            + " WHERE customer_id = ? AND operation = 'DEPOSIT' AND idempotency_key = ?",
        Integer.class,
        customerId,
        key);
  }

  private Integer storedStatus(UUID customerId, UUID key) {
    return jdbc.queryForObject(
        "SELECT response_status FROM wallet.idempotency_records"
            + " WHERE customer_id = ? AND operation = 'DEPOSIT' AND idempotency_key = ?",
        Integer.class,
        customerId,
        key);
  }

  private boolean sameJson(String a, String b) {
    return jdbc.queryForObject("SELECT ?::jsonb = ?::jsonb", Boolean.class, a, b);
  }

  private void insertRecord(UUID customerId, UUID key, OffsetDateTime expiresAt) {
    jdbc.update(
        "INSERT INTO wallet.idempotency_records"
            + " (customer_id, operation, idempotency_key, request_hash, response_status,"
            + " response_body, created_at, expires_at)"
            + " VALUES (?, 'DEPOSIT', ?, ?, 201, '{}'::jsonb, ?, ?)",
        customerId,
        key,
        "a".repeat(64),
        // V10 ck_wallet_idempotency_records_expiry: created a day before it expires.
        expiresAt.minusDays(1),
        expiresAt);
  }

  // New key, replay, reuse -----------------------------------------------------------------------

  @Test
  void contextWiresBothServices() {
    assertThat(contextService).isNotNull();
    assertThat(contextPurge).isNotNull();
    UUID marker = UUID.randomUUID();

    IdempotentResult result =
        contextService.execute(
            deposit(customer, UUID.randomUUID(), "100"), movement(marker, new AtomicInteger()));

    assertThat(result.replayed()).isFalse();
    assertThat(markerCommitted(marker)).isTrue();
  }

  @Test
  void newKeyCommitsTheRecordWithTheMovementAndItsResponse() {
    UUID key = UUID.randomUUID();
    UUID marker = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();

    IdempotentResult result =
        service.execute(deposit(customer, key, "100.00"), movement(marker, runs));

    assertThat(result.replayed()).isFalse();
    assertThat(result.response().status()).isEqualTo(201);
    assertThat(markerCommitted(marker)).isTrue();
    assertThat(storedStatus(customer, key)).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT request_hash = ? AND expires_at = created_at + interval '24 hours'"
                    + " FROM wallet.idempotency_records"
                    + " WHERE customer_id = ? AND operation = 'DEPOSIT' AND idempotency_key = ?",
                Boolean.class,
                deposit(customer, key, "100").requestHash(),
                customer,
                key))
        .isTrue();
  }

  @Test
  void sameKeyAndBodyReplaysTheStoredResponseWithoutASecondMovement() {
    UUID key = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    IdempotentResult original =
        service.execute(deposit(customer, key, "100.00"), movement(first, runs));

    // 100 and 100.00 are the same canonical request.
    IdempotentResult replay =
        service.execute(deposit(customer, key, "100"), movement(second, runs));

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().status()).isEqualTo(201);
    assertThat(sameJson(replay.response().jsonBody(), original.response().jsonBody())).isTrue();
    assertThat(runs).hasValue(1);
    assertThat(markerCommitted(second)).isFalse();
  }

  @Test
  void sameKeyWithAnotherBodyIsKeyReusedAndChangesNothing() {
    UUID key = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    service.execute(deposit(customer, key, "100.00"), movement(UUID.randomUUID(), runs));
    UUID marker = UUID.randomUUID();

    assertThatThrownBy(
            () -> service.execute(deposit(customer, key, "100.01"), movement(marker, runs)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    assertThat(runs).hasValue(1);
    assertThat(markerCommitted(marker)).isFalse();
    assertThat(storedStatus(customer, key)).isEqualTo(201);
  }

  @Test
  void anotherCustomersIdenticalKeyIsIndependent() {
    UUID key = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();

    service.execute(deposit(customer, key, "100"), movement(UUID.randomUUID(), runs));
    IdempotentResult theirs =
        service.execute(deposit(other, key, "999"), movement(UUID.randomUUID(), runs));

    assertThat(theirs.replayed()).isFalse();
    assertThat(runs).hasValue(2);
    assertThat(recordCount(customer, key)).isEqualTo(1);
    assertThat(recordCount(other, key)).isEqualTo(1);
  }

  // Concurrency ---------------------------------------------------------------------------------

  /**
   * AC 8: N requests with the same key and body. The first holds the record in its open money
   * transaction; the others are observed waiting on it, and after its commit each replays it.
   */
  @Test
  void concurrentDuplicatesWaitForTheFirstCommitAndThenReplay() throws Exception {
    int duplicates = 6;
    UUID key = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    int[] holderPid = new int[1];

    Future<IdempotentResult> first =
        pool.submit(
            () ->
                service.execute(
                    deposit(customer, key, "100"),
                    () -> {
                      runs.incrementAndGet();
                      holderPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                      StoredResponse response = moveMoney(UUID.randomUUID());
                      holding.countDown();
                      awaitQuietly(release);
                      return response;
                    }));
    assertThat(holding.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    List<Future<IdempotentResult>> others = new ArrayList<>();
    for (int i = 0; i < duplicates; i++) {
      others.add(
          pool.submit(
              () ->
                  service.execute(
                      deposit(customer, key, "100.00"), movement(UUID.randomUUID(), runs))));
    }
    awaitBlockedBy(holderPid[0], duplicates);
    release.countDown();

    assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).replayed()).isFalse();
    for (Future<IdempotentResult> other : others) {
      assertThat(other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).replayed()).isTrue();
    }
    assertThat(runs).hasValue(1);
    assertThat(recordCount(customer, key)).isEqualTo(1);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("never released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // Failures and rejections ---------------------------------------------------------------------

  /** Owner requirement 2: a failed first request leaves no record, so the retry runs fresh. */
  @Test
  void anUnexpectedFailureRollsBackRecordAndMovementAndTheRetryRunsFresh() {
    UUID key = UUID.randomUUID();
    UUID failed = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.execute(
                    deposit(customer, key, "100"),
                    () -> {
                      moveMoney(failed);
                      throw new IllegalStateException("ledger posting failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(recordCount(customer, key)).isZero();
    assertThat(markerCommitted(failed)).isFalse();

    UUID retried = UUID.randomUUID();
    IdempotentResult retry =
        service.execute(deposit(customer, key, "100"), movement(retried, new AtomicInteger()));
    assertThat(retry.replayed()).isFalse();
    assertThat(markerCommitted(retried)).isTrue();
  }

  @Test
  void anErrorOutsideTheStoredRejectionsLeavesNoRecord() {
    UUID key = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.execute(
                    deposit(customer, key, "100"),
                    rejectingAfterWork(UUID.randomUUID(), ErrorCode.KYC_REQUIRED)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KYC_REQUIRED));

    assertThat(recordCount(customer, key)).isZero();
  }

  /** D05: the movement rolls back; the rejection is committed separately and replayed. */
  @Test
  void aBusinessRejectionIsStoredWithoutTheMovementAndReplayed() {
    UUID key = UUID.randomUUID();
    UUID rejected = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.execute(
                    deposit(customer, key, "100"),
                    rejectingAfterWork(rejected, ErrorCode.INSUFFICIENT_BALANCE)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

    assertThat(markerCommitted(rejected)).isFalse();
    assertThat(storedStatus(customer, key)).isEqualTo(409);
    assertThat(
            jdbc.queryForObject(
                "SELECT response_body ->> 'errorCode' FROM wallet.idempotency_records"
                    + " WHERE customer_id = ? AND operation = 'DEPOSIT' AND idempotency_key = ?",
                String.class,
                customer,
                key))
        .isEqualTo("INSUFFICIENT_BALANCE");

    AtomicInteger runs = new AtomicInteger();
    assertThatThrownBy(
            () ->
                service.execute(
                    deposit(customer, key, "100.00"), movement(UUID.randomUUID(), runs)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
              assertThat(e.getMessage()).isEqualTo("Rejected for test");
            });
    assertThat(runs).hasValue(0);

    // A different body with the rejected key is still a reuse.
    assertThatThrownBy(
            () -> service.execute(deposit(customer, key, "50"), movement(UUID.randomUUID(), runs)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  /**
   * The race of D05: between the rolled-back money transaction and the rejection's own transaction,
   * a request with the same key commits. Its outcome wins; the rejection is not stored over it.
   */
  @Test
  void aSuccessCommittedBetweenRollbackAndRejectionStorageWins() {
    UUID key = UUID.randomUUID();
    UUID competitor = UUID.randomUUID();
    IdempotencyService racing =
        raceBeforeRejectionIsStored(deposit(customer, key, "100"), competitor);

    IdempotentResult result =
        racing.execute(
            deposit(customer, key, "100"),
            rejectingAfterWork(UUID.randomUUID(), ErrorCode.DAILY_LIMIT_EXCEEDED));

    assertThat(result.replayed()).isTrue();
    assertThat(markerCommitted(competitor)).isTrue();
    assertThat(storedStatus(customer, key)).isEqualTo(201);
  }

  @Test
  void anotherBodyCommittedBetweenRollbackAndRejectionStorageMakesItKeyReused() {
    UUID key = UUID.randomUUID();
    IdempotencyService racing =
        raceBeforeRejectionIsStored(deposit(customer, key, "1.00"), UUID.randomUUID());

    assertThatThrownBy(
            () ->
                racing.execute(
                    deposit(customer, key, "100"),
                    rejectingAfterWork(UUID.randomUUID(), ErrorCode.DAILY_LIMIT_EXCEEDED)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    assertThat(storedStatus(customer, key)).isEqualTo(201);
  }

  /**
   * A service whose rejection insert first lets {@code competing} commit through another service on
   * another connection; by then the money transaction has rolled back, so nothing blocks it.
   */
  private IdempotencyService raceBeforeRejectionIsStored(
      IdempotentRequest competing, UUID competitorMarker) {
    IdempotencyRecordRepository spied = spy(records);
    doAnswer(
            invocation -> {
              CompletableFuture.supplyAsync(
                      () ->
                          service.execute(
                              competing, movement(competitorMarker, new AtomicInteger())),
                      pool)
                  .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(spied)
        .insertIfAbsent(
            any(), anyString(), any(), anyString(), notNull(), any(), any(), any(), any());
    return new IdempotencyService(
        spied,
        new IdempotencyProperties(TTL, new IdempotencyProperties.Purge(false, TTL, 2)),
        clock,
        transactionManager);
  }

  // Expiry and purge ----------------------------------------------------------------------------

  /** D04: after the retention period the key counts as absent, as if the purge had run. */
  @Test
  void anExpiredRecordIsReplacedByTheNextRequest() {
    UUID key = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    service.execute(deposit(customer, key, "100"), movement(UUID.randomUUID(), runs));

    clock.advance(TTL.minusSeconds(1));
    assertThat(
            service
                .execute(deposit(customer, key, "100"), movement(UUID.randomUUID(), runs))
                .replayed())
        .isTrue();

    clock.advance(Duration.ofSeconds(1));
    UUID marker = UUID.randomUUID();
    IdempotentResult result =
        service.execute(deposit(customer, key, "250"), movement(marker, runs));

    assertThat(result.replayed()).isFalse();
    assertThat(runs).hasValue(2);
    assertThat(markerCommitted(marker)).isTrue();
    assertThat(recordCount(customer, key)).isEqualTo(1);
  }

  @Test
  void purgeDeletesOnlyExpiredRecordsInBatches() {
    OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
    UUID[] expired = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    for (UUID key : expired) {
      insertRecord(customer, key, now.minusSeconds(1));
    }
    UUID atCutOff = UUID.randomUUID();
    insertRecord(customer, atCutOff, now);
    UUID live = UUID.randomUUID();
    insertRecord(customer, live, now.plusSeconds(1));

    // Batch size 2: at least two statements for the four expired rows.
    assertThat(purge.purgeExpired()).isGreaterThanOrEqualTo(4);

    for (UUID key : expired) {
      assertThat(recordCount(customer, key)).isZero();
    }
    assertThat(recordCount(customer, atCutOff)).isZero();
    assertThat(recordCount(customer, live)).isEqualTo(1);
  }

  /** The purge skips a record a money transaction holds, and does not wait for it. */
  @Test
  void purgeSkipsALockedRecordWithoutWaiting() throws Exception {
    UUID key = UUID.randomUUID();
    insertRecord(customer, key, clock.instant().atOffset(ZoneOffset.UTC).minusSeconds(1));
    try (Connection holder = openTransaction()) {
      queryOne(
          holder,
          "SELECT request_hash FROM wallet.idempotency_records"
              + " WHERE customer_id = ? AND operation = 'DEPOSIT' AND idempotency_key = ?"
              + " FOR UPDATE",
          String.class,
          customer,
          key);

      Future<Integer> run = pool.submit(purge::purgeExpired);
      run.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertThat(recordCount(customer, key)).isEqualTo(1);
      holder.rollback();
    }

    purge.purgeExpired();
    assertThat(recordCount(customer, key)).isZero();
  }

  /**
   * A live movement whose record has passed its expiry by the clock is not deleted under it; the
   * movement still commits its record.
   */
  @Test
  void purgeDuringALiveMovementLeavesItsRecord() throws Exception {
    UUID key = UUID.randomUUID();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Future<IdempotentResult> live =
        pool.submit(
            () ->
                service.execute(
                    deposit(customer, key, "100"),
                    () -> {
                      StoredResponse response = moveMoney(UUID.randomUUID());
                      holding.countDown();
                      awaitQuietly(release);
                      return response;
                    }));
    assertThat(holding.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    // The uncommitted record is invisible to the purge, and the purge does not wait on it.
    clock.advance(TTL.plusHours(1));
    pool.submit(purge::purgeExpired).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    release.countDown();

    assertThat(live.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).replayed()).isFalse();
    assertThat(recordCount(customer, key)).isEqualTo(1);
  }
}
