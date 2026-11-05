package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.wallet.domain.MovementIdentity;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.RequestFingerprint;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decision logic of {@link IdempotencyService} (documentations/02 §1.4; doc 07 D04, D05; doc 08
 * §4B.2 Q14). The database behaviour (locks, rollback, purge) is in {@code IdempotencyServiceIT}.
 */
class IdempotencyServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
  private static final Duration TTL = Duration.ofHours(24);
  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private static final StoredResponse CREATED = new StoredResponse(201, "{\"id\":\"d-1\"}");

  private final IdempotencyRecordRepository records = mock(IdempotencyRecordRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final AtomicInteger movements = new AtomicInteger();

  private IdempotencyService service;
  private IdempotentRequest request;

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    service =
        new IdempotencyService(
            records,
            new IdempotencyProperties(TTL, new IdempotencyProperties.Purge(true, TTL, 10)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            transactions);
    request = new IdempotentRequest(KEY, fingerprint("100.00"));
  }

  private static RequestFingerprint fingerprint(String amount) {
    return RequestFingerprint.builder(
            Operation.DEPOSIT, CUSTOMER, Money.of(new BigDecimal(amount), Money.THB))
        .field("reference", "rent")
        .build();
  }

  private Supplier<StoredResponse> succeeding() {
    return () -> {
      movements.incrementAndGet();
      return CREATED;
    };
  }

  private Supplier<StoredResponse> rejecting(ErrorCode code) {
    return () -> {
      movements.incrementAndGet();
      throw new ApiException(code, "Rejected for test");
    };
  }

  private void insertReturns(Boolean first, Boolean... next) {
    when(records.insertIfAbsent(
            any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(first, next);
  }

  private void existing(IdempotencyRecordRow row) {
    when(records.findForUpdate(CUSTOMER, "DEPOSIT", KEY)).thenReturn(Optional.of(row));
  }

  private static IdempotencyRecordRow success(String hash, Instant expiresAt) {
    return new IdempotencyRecordRow(hash, 201, "{\"id\": \"d-1\"}", null, null, expiresAt);
  }

  private static IdempotencyRecordRow rejection(String hash, ErrorCode code) {
    return new IdempotencyRecordRow(
        hash,
        code.status().value(),
        "{}",
        code.name(),
        "Available balance is lower than the requested amount",
        NOW.plus(TTL));
  }

  // New key ------------------------------------------------------------------------------------

  @Test
  void newKeyRunsTheMovementAndStoresItsResponseInTheSameTransaction() {
    insertReturns(true);

    IdempotentResult result = service.execute(request, succeeding());

    assertThat(result).isEqualTo(new IdempotentResult(CREATED, false));
    assertThat(movements).hasValue(1);
    InOrder order = inOrder(records, transactions);
    order
        .verify(records)
        .insertIfAbsent(
            CUSTOMER, "DEPOSIT", KEY, request.requestHash(), null, null, null, NOW, NOW.plus(TTL));
    order.verify(records).complete(CUSTOMER, "DEPOSIT", KEY, 201, "{\"id\":\"d-1\"}");
    order.verify(transactions).commit(any());
    verify(transactions, never()).rollback(any());
  }

  // Existing key -------------------------------------------------------------------------------

  @Test
  void sameKeyAndHashReplaysTheStoredSuccessWithoutMovingMoney() {
    insertReturns(false);
    existing(success(request.requestHash(), NOW.plus(TTL)));

    IdempotentResult result = service.execute(request, succeeding());

    assertThat(result.replayed()).isTrue();
    assertThat(result.response()).isEqualTo(new StoredResponse(201, "{\"id\": \"d-1\"}"));
    assertThat(movements).hasValue(0);
    verify(records, never()).complete(any(), any(), any(), anyInt(), any());
  }

  @Test
  void sameKeyAndHashReplaysAStoredRejection() {
    insertReturns(false);
    existing(rejection(request.requestHash(), ErrorCode.INSUFFICIENT_BALANCE));

    assertThatThrownBy(() -> service.execute(request, succeeding()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
              assertThat(e.getMessage())
                  .isEqualTo("Available balance is lower than the requested amount");
            });
    assertThat(movements).hasValue(0);
    // One transaction only: a replayed rejection is not stored again.
    verify(transactions, times(1)).getTransaction(any());
  }

  @Test
  void sameKeyWithAnotherBodyIsKeyReused() {
    insertReturns(false);
    existing(success(fingerprint("100.01").sha256Hex(), NOW.plus(TTL)));

    assertThatThrownBy(() -> service.execute(request, succeeding()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    assertThat(movements).hasValue(0);
  }

  @Test
  void anExpiredRecordCountsAsAbsentAndIsReplacedUnderItsLock() {
    insertReturns(false);
    // Expired exactly now: expires_at is the first instant at which the key is no longer kept.
    existing(success(fingerprint("5.00").sha256Hex(), NOW));

    IdempotentResult result = service.execute(request, succeeding());

    assertThat(result).isEqualTo(new IdempotentResult(CREATED, false));
    assertThat(movements).hasValue(1);
    verify(records)
        .replace(
            CUSTOMER, "DEPOSIT", KEY, request.requestHash(), null, null, null, NOW, NOW.plus(TTL));
  }

  @Test
  void aCommittedRecordWithoutOutcomeDoesNotBlockARetry() {
    insertReturns(false);
    existing(new IdempotencyRecordRow(request.requestHash(), null, null, null, null, NOW));

    assertThat(service.execute(request, succeeding()).replayed()).isFalse();
    assertThat(movements).hasValue(1);
  }

  @Test
  void aRecordPurgedBetweenInsertAndLockIsInsertedAgain() {
    insertReturns(false, true);
    when(records.findForUpdate(CUSTOMER, "DEPOSIT", KEY)).thenReturn(Optional.empty());

    assertThat(service.execute(request, succeeding()).replayed()).isFalse();
    verify(records, times(2))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void givesUpAfterABoundedNumberOfClaimAttempts() {
    insertReturns(false);
    when(records.findForUpdate(CUSTOMER, "DEPOSIT", KEY)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.execute(request, succeeding()))
        .isInstanceOf(IllegalStateException.class);
    verify(records, times(IdempotencyService.MAX_CLAIM_ATTEMPTS))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    verify(transactions).rollback(any());
  }

  @Test
  void aStoredRejectionWithoutCodeIsAnInternalError() {
    insertReturns(false);
    existing(new IdempotencyRecordRow(request.requestHash(), 409, "{}", null, null, NOW.plus(TTL)));

    assertThatThrownBy(() -> service.execute(request, succeeding()))
        .isInstanceOf(IllegalStateException.class);
  }

  // Rejections (D05) ---------------------------------------------------------------------------

  @Test
  void aBusinessRejectionRollsBackAndIsStoredInItsOwnTransaction() {
    insertReturns(true, true);

    assertThatThrownBy(() -> service.execute(request, rejecting(ErrorCode.DAILY_LIMIT_EXCEEDED)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED);
              assertThat(e.getMessage()).isEqualTo("Rejected for test");
            });

    InOrder order = inOrder(records, transactions);
    order.verify(transactions).rollback(any());
    order
        .verify(records)
        .insertIfAbsent(
            CUSTOMER,
            "DEPOSIT",
            KEY,
            request.requestHash(),
            409,
            "DAILY_LIMIT_EXCEEDED",
            "Rejected for test",
            NOW,
            NOW.plus(TTL));
    order.verify(transactions).commit(any());
    verify(records, never()).complete(any(), any(), any(), anyInt(), any());
  }

  @Test
  void everyStoredRejectionCodeIsStored() {
    insertReturns(true);
    assertThat(IdempotencyService.STORED_REJECTIONS)
        .containsExactlyInAnyOrder(
            ErrorCode.INSUFFICIENT_BALANCE,
            ErrorCode.SINGLE_LIMIT_EXCEEDED,
            ErrorCode.DAILY_LIMIT_EXCEEDED,
            ErrorCode.WALLET_FROZEN);
    for (ErrorCode code : IdempotencyService.STORED_REJECTIONS) {
      assertThatThrownBy(() -> service.execute(request, rejecting(code)))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
      verify(records)
          .insertIfAbsent(
              any(),
              anyString(),
              any(),
              anyString(),
              eq(code.status().value()),
              eq(code.name()),
              any(),
              any(),
              any());
    }
  }

  // The refusal hook (W5) ------------------------------------------------------------------------

  @Test
  void theRefusalHookRunsOnceInTheTransactionThatStoresTheRefusal() {
    insertReturns(true, true);
    java.util.List<ErrorCode> hooked = new java.util.ArrayList<>();

    assertThatThrownBy(
            () ->
                service.execute(
                    request,
                    rejecting(ErrorCode.INSUFFICIENT_BALANCE),
                    e -> hooked.add(e.errorCode())))
        .isInstanceOf(ApiException.class);

    assertThat(hooked).containsExactly(ErrorCode.INSUFFICIENT_BALANCE);
    // Rolled back money transaction, then the committed refusal transaction.
    verify(transactions).rollback(any());
    verify(transactions).commit(any());
  }

  @Test
  void theRefusalHookDoesNotRunForAReplayARaceOrAnUnstoredError() {
    java.util.List<ErrorCode> hooked = new java.util.ArrayList<>();

    // Replay of a stored refusal.
    insertReturns(false);
    existing(rejection(request.requestHash(), ErrorCode.INSUFFICIENT_BALANCE));
    assertThatThrownBy(() -> service.execute(request, succeeding(), e -> hooked.add(e.errorCode())))
        .isInstanceOf(ApiException.class);

    // Another request with the same key committed between rollback and storage.
    insertReturns(true, false);
    existing(success(request.requestHash(), NOW.plus(TTL)));
    service.execute(
        request, rejecting(ErrorCode.INSUFFICIENT_BALANCE), e -> hooked.add(e.errorCode()));

    // Not a stored rejection.
    insertReturns(true);
    assertThatThrownBy(
            () ->
                service.execute(
                    request, rejecting(ErrorCode.KYC_REQUIRED), e -> hooked.add(e.errorCode())))
        .isInstanceOf(ApiException.class);

    assertThat(hooked).isEmpty();
  }

  @Test
  void aFailingRefusalHookStoresNothing() {
    insertReturns(true, true);

    assertThatThrownBy(
            () ->
                service.execute(
                    request,
                    rejecting(ErrorCode.WALLET_FROZEN),
                    e -> {
                      throw new IllegalStateException("audit failed");
                    }))
        .isInstanceOf(IllegalStateException.class);
    verify(transactions, never()).commit(any());
  }

  @Test
  void aConcurrentSuccessCommittedBeforeTheRejectionIsStoredWins() {
    insertReturns(true, false);
    existing(success(request.requestHash(), NOW.plus(TTL)));

    IdempotentResult result = service.execute(request, rejecting(ErrorCode.INSUFFICIENT_BALANCE));

    assertThat(result.replayed()).isTrue();
    verify(records, never()).replace(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aConcurrentRequestWithAnotherBodyMakesTheRejectionKeyReused() {
    insertReturns(true, false);
    existing(success(fingerprint("1.00").sha256Hex(), NOW.plus(TTL)));

    assertThatThrownBy(() -> service.execute(request, rejecting(ErrorCode.INSUFFICIENT_BALANCE)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @Test
  void aRejectionReplacesAnExpiredRecordFoundInItsOwnTransaction() {
    insertReturns(true, false);
    existing(success(fingerprint("1.00").sha256Hex(), NOW.minusSeconds(1)));

    assertThatThrownBy(() -> service.execute(request, rejecting(ErrorCode.WALLET_FROZEN)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.WALLET_FROZEN));
    verify(records)
        .replace(
            CUSTOMER,
            "DEPOSIT",
            KEY,
            request.requestHash(),
            409,
            "WALLET_FROZEN",
            "Rejected for test",
            NOW,
            NOW.plus(TTL));
  }

  @Test
  void otherApiErrorsAreRethrownAndNotStored() {
    insertReturns(true);

    assertThatThrownBy(() -> service.execute(request, rejecting(ErrorCode.KYC_REQUIRED)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KYC_REQUIRED));
    verify(records, times(1))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    verify(transactions).rollback(any());
    verify(transactions, never()).commit(any());
  }

  @Test
  void unexpectedFailuresAreRethrownAndNotStored() {
    insertReturns(true);

    assertThatThrownBy(
            () ->
                service.execute(
                    request,
                    () -> {
                      throw new IllegalStateException("posting failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("posting failed");
    verify(records, times(1))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    verify(transactions, never()).commit(any());
  }

  @Test
  void aMovementWithoutResponseIsAnInternalError() {
    insertReturns(true);

    assertThatThrownBy(() -> service.execute(request, () -> null))
        .isInstanceOf(NullPointerException.class);
    verify(transactions, never()).commit(any());
  }

  // Preconditions ------------------------------------------------------------------------------

  @Test
  void refusesToJoinAnExistingTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> service.execute(request, succeeding()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verify(transactions, never()).getTransaction(any());
  }

  @Test
  void requiresRequestAndMovement() {
    assertThatThrownBy(() -> service.execute(null, succeeding()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.execute(request, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void requestCoversDepositsAndWithdrawalsOnly() {
    RequestFingerprint transfer =
        RequestFingerprint.builder(
                Operation.TRANSFER, CUSTOMER, Money.of(new BigDecimal("1"), Money.THB))
            .build();
    RequestFingerprint withdrawal =
        RequestFingerprint.builder(
                Operation.WITHDRAWAL, CUSTOMER, Money.of(new BigDecimal("1"), Money.THB))
            .build();

    assertThatThrownBy(() -> new IdempotentRequest(KEY, transfer))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new IdempotentRequest(KEY, withdrawal).operation()).isEqualTo("WITHDRAWAL");
    assertThatThrownBy(() -> new IdempotentRequest(null, withdrawal))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotentRequest(KEY, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void storedResponseIsASuccessWithABody() {
    assertThatThrownBy(() -> new StoredResponse(199, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StoredResponse(409, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StoredResponse(201, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotentResult(null, false))
        .isInstanceOf(NullPointerException.class);
  }

  // Q14 ----------------------------------------------------------------------------------------

  @Test
  void afterPurgeTheStoredMovementIsReturnedOnlyIfEveryFieldMatches() {
    UUID wallet = UUID.randomUUID();
    MovementIdentity stored =
        new MovementIdentity(
            CUSTOMER, Operation.DEPOSIT, wallet, Money.of(new BigDecimal("100.00"), Money.THB));

    IdempotencyService.requireSameMovement(
        new MovementIdentity(
            CUSTOMER, Operation.DEPOSIT, wallet, Money.of(new BigDecimal("100"), Money.THB)),
        stored);

    assertThatThrownBy(
            () ->
                IdempotencyService.requireSameMovement(
                    new MovementIdentity(
                        CUSTOMER,
                        Operation.DEPOSIT,
                        wallet,
                        Money.of(new BigDecimal("100.01"), Money.THB)),
                    stored))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
              assertThat(e.getMessage()).isEqualTo(IdempotencyService.KEY_REUSED);
            });
  }
}
