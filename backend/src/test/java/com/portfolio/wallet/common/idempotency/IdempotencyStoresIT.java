package com.portfolio.wallet.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.fingerprint.CanonicalForm;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.support.MutableClock;
import com.portfolio.wallet.transfer.application.TransferIdempotency;
import com.portfolio.wallet.transfer.application.TransferIdempotencyProperties;
import com.portfolio.wallet.transfer.application.TransferIdempotencyPurgeService;
import com.portfolio.wallet.transfer.application.TransferIdempotentRequest;
import com.portfolio.wallet.transfer.domain.TransferRequestFingerprint;
import com.portfolio.wallet.transfer.persistence.TransferIdempotencyRecordRepository;
import com.portfolio.wallet.wallet.application.IdempotencyProperties;
import com.portfolio.wallet.wallet.application.IdempotencyPurgeService;
import com.portfolio.wallet.wallet.application.IdempotencyService;
import com.portfolio.wallet.wallet.application.IdempotentRequest;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.RequestFingerprint;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The shared engine over both modules' stores against PostgreSQL as {@code wallet_app}
 * (documentations/06 MVP-ADR-06 "Transfers and one engine"; doc 08 §4C.2 Q2, Q3, Q6, §4C.4 item 9).
 *
 * <ul>
 *   <li><b>Shared code, not shared records (Q2):</b> the transfer store reads and writes only
 *       {@code transfer.idempotency_records}, the wallet store only {@code
 *       wallet.idempotency_records}; a record of one is invisible to the other, and each purge
 *       deletes only its own table's rows.
 *   <li>The transfer module's engine end to end on its table: claim, replay, {@code 409},
 *       concurrent duplicates, expiry, purge; {@code RISK_BLOCKED} stored and replayed (Q3), {@code
 *       SYSTEM_ERROR} and a timeout never stored (Q6). The wallet's engine does not store {@code
 *       RISK_BLOCKED}.
 *   <li>The early replay ({@link IdempotencyEngine#replayIfCompleted}, Q3 step 4) on both stores.
 * </ul>
 *
 * As in {@code IdempotencyServiceIT}, the "movement" is a stand-in: it inserts a marker row ({@code
 * wallet.lookup_attempts}) in the same transaction, which shows whether its work committed or
 * rolled back with the record. Services are built over the context's transaction manager with a
 * {@link MutableClock}.
 */
class IdempotencyStoresIT extends DatabaseIntegrationTest {

  private static final Duration TTL = Duration.ofHours(24);
  private static final long TIMEOUT_SECONDS = 30;
  private static final String WALLET_TABLE = "wallet.idempotency_records";
  private static final String TRANSFER_TABLE = "transfer.idempotency_records";

  /** The wallet's stored rejections (PR 4b), for the engine-level cases on the wallet store. */
  private static final Set<ErrorCode> WALLET_REJECTIONS =
      EnumSet.of(
          ErrorCode.INSUFFICIENT_BALANCE,
          ErrorCode.SINGLE_LIMIT_EXCEEDED,
          ErrorCode.DAILY_LIMIT_EXCEEDED,
          ErrorCode.WALLET_FROZEN);

  @Autowired PlatformTransactionManager transactionManager;
  @Autowired TransactionTimeouts timeouts;
  @Autowired TransferIdempotency contextTransfers;
  @Autowired TransferIdempotencyPurgeService contextTransferPurge;
  @Autowired TransferIdempotencyProperties contextTransferProperties;

  @Autowired
  ObjectProvider<com.portfolio.wallet.transfer.application.TransferIdempotencyPurgeSchedule>
      transferSchedule;

  private final MutableClock clock = new MutableClock();
  private IdempotencyRecordRepository walletStore;
  private TransferIdempotencyRecordRepository transferStore;
  private TransferIdempotency transfers;
  private TransferIdempotencyPurgeService transferPurge;
  private IdempotencyService deposits;
  private IdempotencyPurgeService walletPurge;
  private IdempotencyEngine walletEngine;
  private ExecutorService pool;
  private UUID customer;

  /** Which store an engine-level case runs on. */
  enum Store {
    WALLET(WALLET_TABLE, "DEPOSIT"),
    TRANSFER(TRANSFER_TABLE, "TRANSFER");

    final String table;
    final String operation;

    Store(String table, String operation) {
      this.table = table;
      this.operation = operation;
    }
  }

  @BeforeEach
  void setUp() {
    JdbcClient client = JdbcClient.create(dataSource);
    walletStore = new IdempotencyRecordRepository(client);
    transferStore = new TransferIdempotencyRecordRepository(client);
    TransferIdempotencyProperties transferProperties =
        new TransferIdempotencyProperties(
            TTL, new TransferIdempotencyProperties.Purge(false, Duration.ofHours(1), 2));
    transfers =
        new TransferIdempotency(
            transferStore, transferProperties, clock, transactionManager, timeouts);
    transferPurge = new TransferIdempotencyPurgeService(transferStore, transferProperties, clock);
    IdempotencyProperties walletProperties =
        new IdempotencyProperties(
            TTL, new IdempotencyProperties.Purge(false, Duration.ofHours(1), 2));
    deposits =
        new IdempotencyService(walletStore, walletProperties, clock, transactionManager, timeouts);
    walletPurge = new IdempotencyPurgeService(walletStore, walletProperties, clock);
    walletEngine =
        new IdempotencyEngine(
            walletStore, TTL, WALLET_REJECTIONS, clock, transactionManager, () -> {});
    pool = Executors.newCachedThreadPool();
    customer = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  // Fixtures ------------------------------------------------------------------------------------

  private static TransferRequestFingerprint transferFingerprint(UUID sender, String amount) {
    return TransferRequestFingerprint.builder(
            sender, "01234567895", Money.of(new BigDecimal(amount), Money.THB))
        .description("rent")
        .build();
  }

  private TransferIdempotentRequest transfer(UUID key, String amount) {
    return new TransferIdempotentRequest(key, transferFingerprint(customer, amount));
  }

  private IdempotentRequest deposit(UUID key, String amount) {
    return new IdempotentRequest(
        key,
        RequestFingerprint.builder(
                Operation.DEPOSIT, customer, Money.of(new BigDecimal(amount), Money.THB))
            .field("reference", "rent")
            .build());
  }

  private IdempotencyEngine engine(Store store) {
    return store == Store.WALLET
        ? walletEngine
        : new IdempotencyEngine(
            transferStore,
            TTL,
            contextTransfers.storedRejections(),
            clock,
            transactionManager,
            () -> {});
  }

  private IdempotencyRequest request(Store store, UUID key, String body) {
    return new IdempotencyRequest(customer, store.operation, key, CanonicalForm.sha256Hex(body));
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

  private Supplier<com.portfolio.wallet.wallet.application.StoredResponse> walletMovement(
      UUID marker) {
    return () -> {
      StoredResponse response = moveMoney(marker);
      return new com.portfolio.wallet.wallet.application.StoredResponse(
          response.status(), response.jsonBody());
    };
  }

  private boolean markerCommitted(UUID marker) {
    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM wallet.lookup_attempts WHERE user_id = ?)",
        Boolean.class,
        marker);
  }

  /** Rows of the key in {@code table}, whatever the operation. */
  private int records(String table, UUID customerId, UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE customer_id = ? AND idempotency_key = ?",
        Integer.class,
        customerId,
        key);
  }

  private Integer storedStatus(String table, UUID customerId, UUID key) {
    return jdbc.queryForObject(
        "SELECT response_status FROM " + table + " WHERE customer_id = ? AND idempotency_key = ?",
        Integer.class,
        customerId,
        key);
  }

  private String storedField(String table, String expression, UUID key) {
    return jdbc.queryForObject(
        "SELECT "
            + expression
            + " FROM "
            + table
            + " WHERE customer_id = ? AND idempotency_key = ?",
        String.class,
        customer,
        key);
  }

  private void insertRecord(
      String table, String operation, UUID customerId, UUID key, OffsetDateTime expiresAt) {
    jdbc.update(
        "INSERT INTO "
            + table
            + " (customer_id, operation, idempotency_key, request_hash, response_status,"
            + " response_body, created_at, expires_at)"
            + " VALUES (?, ?, ?, ?, 201, '{}'::jsonb, ?, ?)",
        customerId,
        operation,
        key,
        "a".repeat(64),
        // V10, V11 expiry checks: created a day before it expires.
        expiresAt.minusDays(1),
        expiresAt);
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

  private static void assertCode(Throwable e, ErrorCode code) {
    assertThat(e).isInstanceOf(ApiException.class);
    assertThat(((ApiException) e).errorCode()).isEqualTo(code);
  }

  // Context wiring -------------------------------------------------------------------------------

  @Test
  void contextWiresTheTransferComponentsWithTheTransferConfiguration() {
    assertThat(contextTransferProperties.ttl()).isEqualTo(Duration.ofHours(24));
    assertThat(contextTransferProperties.purge().interval()).isEqualTo(Duration.ofHours(1));
    assertThat(contextTransferProperties.purge().batchSize()).isEqualTo(500);
    // Turned off for tests (PostgresTestDatabase), so no background run races them.
    assertThat(contextTransferProperties.purge().enabled()).isFalse();
    assertThat(transferSchedule.getIfAvailable()).isNull();

    // The context's purge (application Clock) deletes an expired transfer row and leaves an
    // expired wallet row with the same customer and key in place: it touches its own table only.
    OffsetDateTime expired = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    UUID purged = UUID.randomUUID();
    insertRecord(TRANSFER_TABLE, "TRANSFER", customer, purged, expired);
    insertRecord(WALLET_TABLE, "DEPOSIT", customer, purged, expired);
    assertThat(contextTransferPurge.purgeExpired()).isGreaterThanOrEqualTo(1);
    assertThat(records(TRANSFER_TABLE, customer, purged)).isZero();
    assertThat(records(WALLET_TABLE, customer, purged)).isEqualTo(1);
    walletPurge.purgeExpired();
    assertThat(records(WALLET_TABLE, customer, purged)).isZero();

    UUID key = UUID.randomUUID();
    UUID marker = UUID.randomUUID();
    IdempotentResult result =
        contextTransfers.execute(transfer(key, "100"), movement(marker, new AtomicInteger()));

    assertThat(result.replayed()).isFalse();
    assertThat(markerCommitted(marker)).isTrue();
    assertThat(records(TRANSFER_TABLE, customer, key)).isEqualTo(1);
    assertThat(records(WALLET_TABLE, customer, key)).isZero();
  }

  // Shared code, not shared records (Q2) ---------------------------------------------------------

  @Test
  void aWalletRecordIsInvisibleToTheTransferStoreAndTheTransferRunsFresh() {
    UUID key = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    deposits.execute(deposit(key, "100"), walletMovement(UUID.randomUUID()));

    assertThat(transferStore.find(customer, "DEPOSIT", key)).isEmpty();
    assertThat(transferStore.find(customer, "TRANSFER", key)).isEmpty();
    UUID marker = UUID.randomUUID();
    IdempotentResult result = transfers.execute(transfer(key, "100"), movement(marker, runs));

    assertThat(result.replayed()).isFalse();
    assertThat(runs).hasValue(1);
    assertThat(markerCommitted(marker)).isTrue();
    assertThat(records(WALLET_TABLE, customer, key)).isEqualTo(1);
    assertThat(records(TRANSFER_TABLE, customer, key)).isEqualTo(1);
    assertThat(storedField(WALLET_TABLE, "operation", key)).isEqualTo("DEPOSIT");
    assertThat(storedField(TRANSFER_TABLE, "operation", key)).isEqualTo("TRANSFER");
    assertThat(storedField(WALLET_TABLE, "request_hash", key))
        .isEqualTo(deposit(key, "100").fingerprint().sha256Hex());
    assertThat(storedField(TRANSFER_TABLE, "request_hash", key))
        .isEqualTo(transfer(key, "100").fingerprint().sha256Hex());
  }

  @Test
  void aTransferRecordIsInvisibleToTheWalletStoreAndTheDepositRunsFresh() {
    UUID key = UUID.randomUUID();
    transfers.execute(transfer(key, "100"), movement(UUID.randomUUID(), new AtomicInteger()));

    assertThat(walletStore.find(customer, "TRANSFER", key)).isEmpty();
    assertThat(walletStore.find(customer, "DEPOSIT", key)).isEmpty();
    assertThat(walletStore.findForUpdate(customer, "TRANSFER", key)).isEmpty();
    UUID marker = UUID.randomUUID();
    var result = deposits.execute(deposit(key, "100"), walletMovement(marker));

    assertThat(result.replayed()).isFalse();
    assertThat(markerCommitted(marker)).isTrue();
    assertThat(records(WALLET_TABLE, customer, key)).isEqualTo(1);
    assertThat(records(TRANSFER_TABLE, customer, key)).isEqualTo(1);
  }

  @Test
  void eachPurgeDeletesOnlyItsOwnTablesExpiredRows() {
    OffsetDateTime expired = clock.instant().atOffset(ZoneOffset.UTC).minusSeconds(1);
    UUID key = UUID.randomUUID();
    insertRecord(WALLET_TABLE, "DEPOSIT", customer, key, expired);
    insertRecord(TRANSFER_TABLE, "TRANSFER", customer, key, expired);

    transferPurge.purgeExpired();
    assertThat(records(TRANSFER_TABLE, customer, key)).isZero();
    assertThat(records(WALLET_TABLE, customer, key)).isEqualTo(1);

    UUID other = UUID.randomUUID();
    insertRecord(TRANSFER_TABLE, "TRANSFER", customer, other, expired);
    walletPurge.purgeExpired();
    assertThat(records(WALLET_TABLE, customer, key)).isZero();
    assertThat(records(TRANSFER_TABLE, customer, other)).isEqualTo(1);
    transferPurge.purgeExpired();
    assertThat(records(TRANSFER_TABLE, customer, other)).isZero();
  }

  // The transfer engine on its table --------------------------------------------------------------

  @Test
  void aNewTransferKeyCommitsTheRecordWithTheMovementAndItsResponse() {
    UUID key = UUID.randomUUID();
    UUID marker = UUID.randomUUID();

    IdempotentResult result =
        transfers.execute(transfer(key, "100.00"), movement(marker, new AtomicInteger()));

    assertThat(result.replayed()).isFalse();
    assertThat(markerCommitted(marker)).isTrue();
    assertThat(storedStatus(TRANSFER_TABLE, customer, key)).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT request_hash = ? AND operation = 'TRANSFER'"
                    + " AND expires_at = created_at + interval '24 hours'"
                    + " FROM transfer.idempotency_records"
                    + " WHERE customer_id = ? AND idempotency_key = ?",
                Boolean.class,
                transfer(key, "100").fingerprint().sha256Hex(),
                customer,
                key))
        .isTrue();
  }

  @Test
  void sameTransferKeyAndBodyReplaysAndAnotherBodyIsKeyReused() {
    UUID key = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    transfers.execute(transfer(key, "100.00"), movement(UUID.randomUUID(), runs));

    UUID second = UUID.randomUUID();
    IdempotentResult replay = transfers.execute(transfer(key, "100"), movement(second, runs));
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().status()).isEqualTo(201);
    assertThat(markerCommitted(second)).isFalse();

    UUID third = UUID.randomUUID();
    assertThatThrownBy(() -> transfers.execute(transfer(key, "100.01"), movement(third, runs)))
        .satisfies(e -> assertCode(e, ErrorCode.IDEMPOTENCY_KEY_REUSED));
    assertThat(markerCommitted(third)).isFalse();
    assertThat(runs).hasValue(1);
    assertThat(records(TRANSFER_TABLE, customer, key)).isEqualTo(1);
  }

  /** §4C.4 item 9: concurrent duplicates create one transfer; the others wait and then replay. */
  @Test
  void concurrentDuplicateTransfersWaitForTheFirstCommitAndThenReplay() throws Exception {
    int duplicates = 4;
    UUID key = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    int[] holderPid = new int[1];

    Future<IdempotentResult> first =
        pool.submit(
            () ->
                transfers.execute(
                    transfer(key, "100"),
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
              () -> transfers.execute(transfer(key, "100.00"), movement(UUID.randomUUID(), runs))));
    }
    awaitBlockedBy(holderPid[0], duplicates);
    release.countDown();

    assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).replayed()).isFalse();
    for (Future<IdempotentResult> other : others) {
      assertThat(other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).replayed()).isTrue();
    }
    assertThat(runs).hasValue(1);
    assertThat(records(TRANSFER_TABLE, customer, key)).isEqualTo(1);
  }

  /**
   * Q3: {@code RISK_BLOCKED} is stored in its own transaction with what the hook writes (the {@code
   * BLOCKED} row in step 5), and replayed; the movement's own work rolls back.
   */
  @Test
  void riskBlockedIsStoredWithItsHookAndReplayedForTransfers() {
    UUID key = UUID.randomUUID();
    UUID work = UUID.randomUUID();
    UUID blockedRow = UUID.randomUUID();
    AtomicInteger hooks = new AtomicInteger();

    assertThatThrownBy(
            () ->
                transfers.execute(
                    transfer(key, "100"),
                    rejectingAfterWork(work, ErrorCode.RISK_BLOCKED),
                    refusal -> {
                      hooks.incrementAndGet();
                      moveMoney(blockedRow);
                    }))
        .satisfies(e -> assertCode(e, ErrorCode.RISK_BLOCKED));

    assertThat(markerCommitted(work)).isFalse();
    assertThat(markerCommitted(blockedRow)).isTrue();
    assertThat(hooks).hasValue(1);
    assertThat(storedStatus(TRANSFER_TABLE, customer, key)).isEqualTo(403);
    assertThat(storedField(TRANSFER_TABLE, "response_body ->> 'errorCode'", key))
        .isEqualTo("RISK_BLOCKED");

    AtomicInteger runs = new AtomicInteger();
    assertThatThrownBy(
            () ->
                transfers.execute(
                    transfer(key, "100.00"),
                    movement(UUID.randomUUID(), runs),
                    refusal -> hooks.incrementAndGet()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RISK_BLOCKED);
              assertThat(e.getMessage()).isEqualTo("Rejected for test");
            });
    assertThat(runs).hasValue(0);
    assertThat(hooks).hasValue(1);
    assertThatThrownBy(() -> transfers.replayIfCompleted(transfer(key, "100")))
        .satisfies(e -> assertCode(e, ErrorCode.RISK_BLOCKED));
  }

  @Test
  void riskBlockedIsNotStoredForDepositsAndWithdrawals() {
    UUID key = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                deposits.execute(
                    deposit(key, "100"),
                    () -> {
                      moveMoney(UUID.randomUUID());
                      throw new ApiException(ErrorCode.RISK_BLOCKED, "Rejected for test");
                    }))
        .satisfies(e -> assertCode(e, ErrorCode.RISK_BLOCKED));

    assertThat(records(WALLET_TABLE, customer, key)).isZero();
    UUID marker = UUID.randomUUID();
    assertThat(deposits.execute(deposit(key, "100"), walletMovement(marker)).replayed()).isFalse();
    assertThat(markerCommitted(marker)).isTrue();
  }

  /** Q3, Q6: a risk failure or a timeout leaves no record, so the retry runs fresh. */
  @Test
  void systemErrorAndATimeoutAreNeverStoredForTransfers() {
    UUID key = UUID.randomUUID();
    UUID failed = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                transfers.execute(
                    transfer(key, "100"), rejectingAfterWork(failed, ErrorCode.SYSTEM_ERROR)))
        .satisfies(e -> assertCode(e, ErrorCode.SYSTEM_ERROR));
    assertThat(records(TRANSFER_TABLE, customer, key)).isZero();
    assertThat(markerCommitted(failed)).isFalse();

    assertThatThrownBy(
            () ->
                transfers.execute(
                    transfer(key, "100"),
                    () -> {
                      moveMoney(UUID.randomUUID());
                      throw new QueryTimeoutException("canceling statement due to lock timeout");
                    }))
        .isInstanceOf(QueryTimeoutException.class);
    assertThat(records(TRANSFER_TABLE, customer, key)).isZero();
    assertThat(transfers.replayIfCompleted(transfer(key, "100"))).isEmpty();

    UUID retried = UUID.randomUUID();
    IdempotentResult retry =
        transfers.execute(transfer(key, "100"), movement(retried, new AtomicInteger()));
    assertThat(retry.replayed()).isFalse();
    assertThat(markerCommitted(retried)).isTrue();
  }

  @Test
  void anExpiredTransferRecordIsReplacedByTheNextRequest() {
    UUID key = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    transfers.execute(transfer(key, "100"), movement(UUID.randomUUID(), runs));

    clock.advance(TTL.minusSeconds(1));
    assertThat(
            transfers.execute(transfer(key, "100"), movement(UUID.randomUUID(), runs)).replayed())
        .isTrue();

    clock.advance(Duration.ofSeconds(1));
    UUID marker = UUID.randomUUID();
    IdempotentResult result = transfers.execute(transfer(key, "250"), movement(marker, runs));

    assertThat(result.replayed()).isFalse();
    assertThat(runs).hasValue(2);
    assertThat(markerCommitted(marker)).isTrue();
    assertThat(records(TRANSFER_TABLE, customer, key)).isEqualTo(1);
    assertThat(storedField(TRANSFER_TABLE, "request_hash", key))
        .isEqualTo(transfer(key, "250").fingerprint().sha256Hex());
  }

  @Test
  void theTransferPurgeDeletesOnlyExpiredRecordsInBatches() {
    OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
    UUID[] expired = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    for (UUID key : expired) {
      insertRecord(TRANSFER_TABLE, "TRANSFER", customer, key, now.minusSeconds(1));
    }
    UUID atCutOff = UUID.randomUUID();
    insertRecord(TRANSFER_TABLE, "TRANSFER", customer, atCutOff, now);
    UUID live = UUID.randomUUID();
    insertRecord(TRANSFER_TABLE, "TRANSFER", customer, live, now.plusSeconds(1));

    // Batch size 2: at least two statements for the four expired rows.
    assertThat(transferPurge.purgeExpired()).isGreaterThanOrEqualTo(4);

    for (UUID key : expired) {
      assertThat(records(TRANSFER_TABLE, customer, key)).isZero();
    }
    assertThat(records(TRANSFER_TABLE, customer, atCutOff)).isZero();
    assertThat(records(TRANSFER_TABLE, customer, live)).isEqualTo(1);
  }

  @Test
  void theTransferPurgeSkipsALockedRecordWithoutWaiting() throws Exception {
    UUID key = UUID.randomUUID();
    insertRecord(
        TRANSFER_TABLE,
        "TRANSFER",
        customer,
        key,
        clock.instant().atOffset(ZoneOffset.UTC).minusSeconds(1));
    try (Connection holder = openTransaction()) {
      queryOne(
          holder,
          "SELECT request_hash FROM transfer.idempotency_records"
              + " WHERE customer_id = ? AND operation = 'TRANSFER' AND idempotency_key = ?"
              + " FOR UPDATE",
          String.class,
          customer,
          key);

      pool.submit(transferPurge::purgeExpired).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertThat(records(TRANSFER_TABLE, customer, key)).isEqualTo(1);
      holder.rollback();
    }

    transferPurge.purgeExpired();
    assertThat(records(TRANSFER_TABLE, customer, key)).isZero();
  }

  // Early replay on both stores (Q3 step 4) ------------------------------------------------------

  @ParameterizedTest
  @EnumSource(Store.class)
  void earlyReplayOfAnUnknownKeyIsEmpty(Store store) {
    assertThat(engine(store).replayIfCompleted(request(store, UUID.randomUUID(), "a"))).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(Store.class)
  void earlyReplayReturnsTheCommittedSuccessWithoutWriting(Store store) {
    IdempotencyEngine engine = engine(store);
    UUID key = UUID.randomUUID();
    IdempotentResult original =
        engine.execute(request(store, key, "a"), movement(UUID.randomUUID(), new AtomicInteger()));
    String before = storedField(store.table, "created_at::text || expires_at::text", key);

    Optional<IdempotentResult> replay = engine.replayIfCompleted(request(store, key, "a"));

    assertThat(replay).isPresent();
    assertThat(replay.get().replayed()).isTrue();
    assertThat(replay.get().response().status()).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT ?::jsonb = ?::jsonb",
                Boolean.class,
                replay.get().response().jsonBody(),
                original.response().jsonBody()))
        .isTrue();
    assertThat(storedField(store.table, "created_at::text || expires_at::text", key))
        .isEqualTo(before);
  }

  @ParameterizedTest
  @EnumSource(Store.class)
  void earlyReplayThrowsAStoredRejectionAgain(Store store) {
    IdempotencyEngine engine = engine(store);
    UUID key = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                engine.execute(
                    request(store, key, "a"),
                    rejectingAfterWork(UUID.randomUUID(), ErrorCode.INSUFFICIENT_BALANCE)))
        .satisfies(e -> assertCode(e, ErrorCode.INSUFFICIENT_BALANCE));

    assertThatThrownBy(() -> engine.replayIfCompleted(request(store, key, "a")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
              assertThat(e.getMessage()).isEqualTo("Rejected for test");
            });
  }

  @ParameterizedTest
  @EnumSource(Store.class)
  void earlyReplayOfAnotherBodyIsKeyReused(Store store) {
    IdempotencyEngine engine = engine(store);
    UUID key = UUID.randomUUID();
    engine.execute(request(store, key, "a"), movement(UUID.randomUUID(), new AtomicInteger()));

    assertThatThrownBy(() -> engine.replayIfCompleted(request(store, key, "b")))
        .satisfies(e -> assertCode(e, ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @ParameterizedTest
  @EnumSource(Store.class)
  void earlyReplayTreatsARecordExpiredByTheApplicationClockAsAbsent(Store store) {
    IdempotencyEngine engine = engine(store);
    UUID key = UUID.randomUUID();
    engine.execute(request(store, key, "a"), movement(UUID.randomUUID(), new AtomicInteger()));

    clock.advance(TTL.minusSeconds(1));
    assertThat(engine.replayIfCompleted(request(store, key, "a"))).isPresent();
    clock.advance(Duration.ofSeconds(1));
    // Not purged yet, but expired: absent, for the same body and for another one.
    assertThat(records(store.table, customer, key)).isEqualTo(1);
    assertThat(engine.replayIfCompleted(request(store, key, "a"))).isEmpty();
    assertThat(engine.replayIfCompleted(request(store, key, "b"))).isEmpty();
  }

  /** An open money transaction's claim is not visible, and the early replay does not wait on it. */
  @ParameterizedTest
  @EnumSource(Store.class)
  void earlyReplayDoesNotSeeOrWaitForAnUncommittedClaim(Store store) throws Exception {
    IdempotencyEngine engine = engine(store);
    UUID key = UUID.randomUUID();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Future<IdempotentResult> live =
        pool.submit(
            () ->
                engine.execute(
                    request(store, key, "a"),
                    () -> {
                      StoredResponse response = moveMoney(UUID.randomUUID());
                      holding.countDown();
                      awaitQuietly(release);
                      return response;
                    }));
    assertThat(holding.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    Optional<IdempotentResult> early =
        pool.submit(() -> engine.replayIfCompleted(request(store, key, "a")))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(early).isEmpty();
    release.countDown();

    assertThat(live.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).replayed()).isFalse();
    assertThat(engine.replayIfCompleted(request(store, key, "a"))).isPresent();
  }

  @Test
  void transferEarlyReplayThroughTheModuleComponent() {
    UUID key = UUID.randomUUID();
    assertThat(transfers.replayIfCompleted(transfer(key, "100"))).isEmpty();
    transfers.execute(transfer(key, "100"), movement(UUID.randomUUID(), new AtomicInteger()));

    assertThat(transfers.replayIfCompleted(transfer(key, "100.00"))).isPresent();
    assertThatThrownBy(() -> transfers.replayIfCompleted(transfer(key, "100.01")))
        .satisfies(e -> assertCode(e, ErrorCode.IDEMPOTENCY_KEY_REUSED));
    // Another sender's identical key is a separate key.
    assertThat(
            transfers.replayIfCompleted(
                new TransferIdempotentRequest(key, transferFingerprint(UUID.randomUUID(), "100"))))
        .isEmpty();
  }
}
