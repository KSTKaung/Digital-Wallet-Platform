package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.idempotency.IdempotentResult;
import com.portfolio.wallet.common.idempotency.StoredResponse;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.transfer.domain.TransferRequestFingerprint;
import com.portfolio.wallet.transfer.persistence.TransferIdempotencyRecordRepository;
import com.portfolio.wallet.transfer.persistence.TransferIdempotencyRecordRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The transfer module's idempotency wiring (doc 08 §4C.2 Q2, Q3, Q6): its own store, TTL and stored
 * rejections, its purge and its schedule. The engine's decisions are in {@code
 * IdempotencyEngineTest}; the database in {@code IdempotencyStoresIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferIdempotencyTest {

  private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
  private static final Duration TTL = Duration.ofHours(24);
  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final TransferIdempotencyRecordRepository records =
      mock(TransferIdempotencyRecordRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final TransferIdempotencyProperties properties =
      new TransferIdempotencyProperties(
          TTL, new TransferIdempotencyProperties.Purge(true, Duration.ofHours(1), 2));

  private TransferIdempotency idempotency;
  private TransferIdempotentRequest request;

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    idempotency = new TransferIdempotency(records, properties, CLOCK, transactions);
    request = new TransferIdempotentRequest(KEY, fingerprint("100.00"));
  }

  private static TransferRequestFingerprint fingerprint(String amount) {
    return TransferRequestFingerprint.builder(
            SENDER, "01234567895", Money.of(new BigDecimal(amount), Money.THB))
        .description("rent")
        .build();
  }

  private static java.util.function.Supplier<StoredResponse> rejecting(ErrorCode code) {
    return () -> {
      throw new ApiException(code, "Rejected for test");
    };
  }

  @Test
  void storesTheFourMoneyRefusalsAndRiskBlocked() {
    assertThat(idempotency.storedRejections())
        .containsExactlyInAnyOrder(
            ErrorCode.INSUFFICIENT_BALANCE,
            ErrorCode.SINGLE_LIMIT_EXCEEDED,
            ErrorCode.DAILY_LIMIT_EXCEEDED,
            ErrorCode.WALLET_FROZEN,
            ErrorCode.RISK_BLOCKED)
        .isEqualTo(TransferIdempotency.STORED_REJECTIONS)
        .doesNotContain(ErrorCode.SYSTEM_ERROR);
  }

  @Test
  void aNewKeyIsClaimedAndCompletedInTheTransferTableWithTheTransferTtl() {
    when(records.insertIfAbsent(
            any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    IdempotentResult result = idempotency.execute(request, () -> new StoredResponse(201, "{}"));

    assertThat(result.replayed()).isFalse();
    verify(records)
        .insertIfAbsent(
            SENDER,
            "TRANSFER",
            KEY,
            request.fingerprint().sha256Hex(),
            null,
            null,
            null,
            NOW,
            NOW.plus(TTL));
    verify(records).complete(SENDER, "TRANSFER", KEY, 201, "{}");
  }

  @Test
  void riskBlockedIsStoredWithItsHook() {
    when(records.insertIfAbsent(
            any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    List<ErrorCode> hooked = new ArrayList<>();

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    request, rejecting(ErrorCode.RISK_BLOCKED), e -> hooked.add(e.errorCode())))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RISK_BLOCKED));

    assertThat(hooked).containsExactly(ErrorCode.RISK_BLOCKED);
    verify(records)
        .insertIfAbsent(
            eq(SENDER),
            eq("TRANSFER"),
            eq(KEY),
            anyString(),
            eq(403),
            eq("RISK_BLOCKED"),
            eq("Rejected for test"),
            eq(NOW),
            eq(NOW.plus(TTL)));
  }

  @Test
  void systemErrorIsNotStored() {
    when(records.insertIfAbsent(
            any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    assertThatThrownBy(() -> idempotency.execute(request, rejecting(ErrorCode.SYSTEM_ERROR)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
    verify(records, never())
        .insertIfAbsent(
            any(), anyString(), any(), anyString(), eq(500), any(), any(), any(), any());
    verify(transactions, never()).commit(any());
  }

  @Test
  void earlyReplayReadsTheTransferRecordWithoutLocking() {
    when(records.find(SENDER, "TRANSFER", KEY))
        .thenReturn(
            Optional.of(
                new TransferIdempotencyRecordRow(
                    request.fingerprint().sha256Hex(),
                    201,
                    "{\"id\": 1}",
                    null,
                    null,
                    NOW.plus(TTL))));

    assertThat(idempotency.replayIfCompleted(request))
        .contains(new IdempotentResult(new StoredResponse(201, "{\"id\": 1}"), true));
    verify(records, never()).findForUpdate(any(), any(), any());
  }

  @Test
  void earlyReplayOfAnotherBodyIsKeyReused() {
    when(records.find(SENDER, "TRANSFER", KEY))
        .thenReturn(
            Optional.of(
                new TransferIdempotencyRecordRow(
                    fingerprint("100.01").sha256Hex(), 201, "{}", null, null, NOW.plus(TTL))));

    assertThatThrownBy(() -> idempotency.replayIfCompleted(request))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @Test
  void requiresARequest() {
    assertThatThrownBy(() -> idempotency.execute(null, () -> new StoredResponse(201, "{}")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> idempotency.replayIfCompleted(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aRequestNeedsKeyAndFingerprintAndMapsToTheTransferOperation() {
    assertThat(request.toEngineRequest().operation()).isEqualTo("TRANSFER");
    assertThat(request.toEngineRequest().customerId()).isEqualTo(SENDER);
    assertThat(request.toEngineRequest().key()).isEqualTo(KEY);
    assertThat(request.toEngineRequest().requestHash())
        .isEqualTo(request.fingerprint().sha256Hex());
    assertThatThrownBy(() -> new TransferIdempotentRequest(null, fingerprint("1")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TransferIdempotentRequest(KEY, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aRequestPrintsNeitherTheKeyNorTheHash() {
    assertThat(request.toString())
        .isEqualTo("TransferIdempotentRequest[operation=TRANSFER]")
        .doesNotContain(
            KEY.toString(),
            request.fingerprint().sha256Hex(),
            SENDER.toString(),
            "01234567895",
            "rent");
  }

  // Properties -----------------------------------------------------------------------------------

  @Test
  void propertiesAreCheckedAtStartupUnderTheTransferPrefix() {
    TransferIdempotencyProperties.Purge validPurge =
        new TransferIdempotencyProperties.Purge(true, Duration.ofHours(1), 1);

    assertThat(new TransferIdempotencyProperties(Duration.ofSeconds(1), validPurge).ttl())
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(new TransferIdempotencyProperties(Duration.ofDays(30), validPurge).ttl())
        .isEqualTo(Duration.ofDays(30));
    assertThatThrownBy(() -> new TransferIdempotencyProperties(Duration.ofMillis(999), validPurge))
        .hasMessageContaining("app.transfer.idempotency.ttl");
    assertThatThrownBy(() -> new TransferIdempotencyProperties(null, validPurge))
        .hasMessageContaining("app.transfer.idempotency.ttl");
    assertThatThrownBy(
            () -> new TransferIdempotencyProperties(Duration.ofDays(30).plusSeconds(1), validPurge))
        .hasMessageContaining("app.transfer.idempotency.ttl");
    assertThatThrownBy(() -> new TransferIdempotencyProperties(Duration.ofHours(24), null))
        .hasMessageContaining("app.transfer.idempotency.purge");
    assertThatThrownBy(() -> new TransferIdempotencyProperties.Purge(true, null, 1))
        .hasMessageContaining("app.transfer.idempotency.purge.interval");
    assertThatThrownBy(() -> new TransferIdempotencyProperties.Purge(true, Duration.ZERO, 1))
        .hasMessageContaining("app.transfer.idempotency.purge.interval");
    assertThatThrownBy(() -> new TransferIdempotencyProperties.Purge(true, Duration.ofHours(1), 0))
        .hasMessageContaining("app.transfer.idempotency.purge.batch-size");
  }

  // Purge ----------------------------------------------------------------------------------------

  @Test
  void thePurgeDeletesTransferRecordsInConfiguredBatches() {
    TransferIdempotencyPurgeService purge =
        new TransferIdempotencyPurgeService(records, properties, CLOCK);
    when(records.deleteExpired(NOW, 2)).thenReturn(2, 1);

    assertThat(purge.purgeExpired()).isEqualTo(3);
    verify(records, org.mockito.Mockito.times(2)).deleteExpired(NOW, 2);
  }

  @Test
  void aPurgeFailureIsLoggedByClassOnlyWithTheTransferTable(CapturedOutput output) {
    TransferIdempotencyPurgeService purge =
        new TransferIdempotencyPurgeService(records, properties, CLOCK);
    when(records.deleteExpired(any(), anyInt()))
        .thenThrow(new DataIntegrityViolationException("key (customer_id)=(7c1e-secret)"));

    assertThatCode(() -> assertThat(purge.purgeExpired()).isZero()).doesNotThrowAnyException();
    assertThat(output.getAll())
        .contains("table=" + TransferIdempotencyPurgeService.TABLE)
        .contains("table=transfer.idempotency_records")
        .contains("DataIntegrityViolationException")
        .doesNotContain("7c1e-secret");
  }

  @Test
  void theScheduledJobDelegatesToThePurge() {
    TransferIdempotencyPurgeService purge = mock(TransferIdempotencyPurgeService.class);

    new TransferIdempotencyPurgeSchedule(purge).run();

    verify(purge).purgeExpired();
  }
}
