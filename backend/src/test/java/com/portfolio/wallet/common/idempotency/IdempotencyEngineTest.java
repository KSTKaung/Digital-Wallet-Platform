package com.portfolio.wallet.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.fingerprint.CanonicalForm;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decision logic of {@link IdempotencyEngine} over a mocked {@link IdempotencyStore}
 * (documentations/02 §1.4; doc 06 MVP-ADR-06; doc 07 D04, D05; doc 08 §4B.2 Q14, §4C.2 Q2, Q3, Q6).
 * The same cases as the wallet's {@code IdempotencyServiceTest}, now against the shared engine,
 * with the transfer module's set of stored rejections ({@code RISK_BLOCKED} included), plus the
 * early replay of Q3 step 4. The database behaviour is in {@code IdempotencyStoresIT}.
 */
class IdempotencyEngineTest {

  private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
  private static final Duration TTL = Duration.ofHours(24);
  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private static final String OPERATION = "TRANSFER";
  private static final String HASH = CanonicalForm.sha256Hex("request-1");
  private static final String OTHER_HASH = CanonicalForm.sha256Hex("request-2");
  private static final StoredResponse CREATED = new StoredResponse(201, "{\"id\":\"t-1\"}");

  /** The transfer module's set (Q3). */
  private static final Set<ErrorCode> TRANSFER_REJECTIONS =
      EnumSet.of(
          ErrorCode.INSUFFICIENT_BALANCE,
          ErrorCode.SINGLE_LIMIT_EXCEEDED,
          ErrorCode.DAILY_LIMIT_EXCEEDED,
          ErrorCode.WALLET_FROZEN,
          ErrorCode.RISK_BLOCKED);

  /** The wallet module's set (PR 4b). */
  private static final Set<ErrorCode> WALLET_REJECTIONS =
      EnumSet.of(
          ErrorCode.INSUFFICIENT_BALANCE,
          ErrorCode.SINGLE_LIMIT_EXCEEDED,
          ErrorCode.DAILY_LIMIT_EXCEEDED,
          ErrorCode.WALLET_FROZEN);

  private final IdempotencyStore store = mock(IdempotencyStore.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final Runnable preparer = mock(Runnable.class);
  private final AtomicInteger movements = new AtomicInteger();
  private final IdempotencyRequest request = new IdempotencyRequest(CUSTOMER, OPERATION, KEY, HASH);

  private IdempotencyEngine engine;

  record Row(
      String requestHash,
      Integer responseStatus,
      String responseBody,
      String errorCode,
      String errorMessage,
      Instant expiresAt)
      implements IdempotencyRecord {}

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    engine = engine(TRANSFER_REJECTIONS);
  }

  private IdempotencyEngine engine(Set<ErrorCode> rejections) {
    return new IdempotencyEngine(
        store, TTL, rejections, Clock.fixed(NOW, ZoneOffset.UTC), transactions, preparer);
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
    when(store.insertIfAbsent(
            any(), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(first, next);
  }

  private void existing(Row row) {
    doReturn(Optional.of(row)).when(store).findForUpdate(CUSTOMER, OPERATION, KEY);
  }

  private void committed(Row row) {
    doReturn(Optional.of(row)).when(store).find(CUSTOMER, OPERATION, KEY);
  }

  private static Row success(String hash, Instant expiresAt) {
    return new Row(hash, 201, "{\"id\": \"t-1\"}", null, null, expiresAt);
  }

  private static Row rejection(String hash, ErrorCode code) {
    return new Row(
        hash, code.status().value(), "{}", code.name(), "Rejected earlier", NOW.plus(TTL));
  }

  private static void assertCode(Throwable e, ErrorCode code) {
    assertThat(e).isInstanceOf(ApiException.class);
    assertThat(((ApiException) e).errorCode()).isEqualTo(code);
  }

  // New key ------------------------------------------------------------------------------------

  @Test
  void newKeyRunsTheMovementAndStoresItsResponseInTheSameTransaction() {
    insertReturns(true);

    IdempotentResult result = engine.execute(request, succeeding());

    assertThat(result).isEqualTo(new IdempotentResult(CREATED, false));
    assertThat(movements).hasValue(1);
    InOrder order = inOrder(store, transactions);
    order
        .verify(store)
        .insertIfAbsent(CUSTOMER, OPERATION, KEY, HASH, null, null, null, NOW, NOW.plus(TTL));
    order.verify(store).complete(CUSTOMER, OPERATION, KEY, 201, "{\"id\":\"t-1\"}");
    order.verify(transactions).commit(any());
    verify(transactions, never()).rollback(any());
  }

  // Existing key -------------------------------------------------------------------------------

  @Test
  void sameKeyAndHashReplaysTheStoredSuccessWithoutMovingMoney() {
    insertReturns(false);
    existing(success(HASH, NOW.plus(TTL)));

    IdempotentResult result = engine.execute(request, succeeding());

    assertThat(result.replayed()).isTrue();
    assertThat(result.response()).isEqualTo(new StoredResponse(201, "{\"id\": \"t-1\"}"));
    assertThat(movements).hasValue(0);
    verify(store, never()).complete(any(), any(), any(), anyInt(), any());
  }

  @Test
  void sameKeyAndHashReplaysAStoredRejection() {
    insertReturns(false);
    existing(rejection(HASH, ErrorCode.RISK_BLOCKED));

    assertThatThrownBy(() -> engine.execute(request, succeeding()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RISK_BLOCKED);
              assertThat(e.getMessage()).isEqualTo("Rejected earlier");
            });
    assertThat(movements).hasValue(0);
    // One transaction only: a replayed rejection is not stored again.
    verify(transactions, times(1)).getTransaction(any());
  }

  @Test
  void sameKeyWithAnotherBodyIsKeyReused() {
    insertReturns(false);
    existing(success(OTHER_HASH, NOW.plus(TTL)));

    assertThatThrownBy(() -> engine.execute(request, succeeding()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
              assertThat(e.getMessage()).isEqualTo(IdempotencyEngine.KEY_REUSED);
            });
    assertThat(movements).hasValue(0);
  }

  @Test
  void anExpiredRecordCountsAsAbsentAndIsReplacedUnderItsLock() {
    insertReturns(false);
    // Expired exactly now: expires_at is the first instant at which the key is no longer kept.
    existing(success(OTHER_HASH, NOW));

    IdempotentResult result = engine.execute(request, succeeding());

    assertThat(result).isEqualTo(new IdempotentResult(CREATED, false));
    assertThat(movements).hasValue(1);
    verify(store).replace(CUSTOMER, OPERATION, KEY, HASH, null, null, null, NOW, NOW.plus(TTL));
  }

  @Test
  void aCommittedRecordWithoutOutcomeDoesNotBlockARetry() {
    insertReturns(false);
    existing(new Row(HASH, null, null, null, null, NOW.plus(TTL)));

    assertThat(engine.execute(request, succeeding()).replayed()).isFalse();
    assertThat(movements).hasValue(1);
  }

  @Test
  void aRecordPurgedBetweenInsertAndLockIsInsertedAgain() {
    insertReturns(false, true);
    doReturn(Optional.empty()).when(store).findForUpdate(CUSTOMER, OPERATION, KEY);

    assertThat(engine.execute(request, succeeding()).replayed()).isFalse();
    verify(store, times(2))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void givesUpAfterABoundedNumberOfClaimAttempts() {
    insertReturns(false);
    doReturn(Optional.empty()).when(store).findForUpdate(CUSTOMER, OPERATION, KEY);

    assertThatThrownBy(() -> engine.execute(request, succeeding()))
        .isInstanceOf(IllegalStateException.class);
    verify(store, times(IdempotencyEngine.MAX_CLAIM_ATTEMPTS))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    verify(transactions).rollback(any());
  }

  @Test
  void aStoredRejectionWithoutCodeIsAnInternalError() {
    insertReturns(false);
    existing(new Row(HASH, 409, "{}", null, null, NOW.plus(TTL)));

    assertThatThrownBy(() -> engine.execute(request, succeeding()))
        .isInstanceOf(IllegalStateException.class);
  }

  // Rejections (D05; Q3) -------------------------------------------------------------------------

  @Test
  void aBusinessRejectionRollsBackAndIsStoredInItsOwnTransaction() {
    insertReturns(true, true);

    assertThatThrownBy(() -> engine.execute(request, rejecting(ErrorCode.DAILY_LIMIT_EXCEEDED)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED);
              assertThat(e.getMessage()).isEqualTo("Rejected for test");
            });

    InOrder order = inOrder(store, transactions);
    order.verify(transactions).rollback(any());
    order
        .verify(store)
        .insertIfAbsent(
            CUSTOMER,
            OPERATION,
            KEY,
            HASH,
            409,
            "DAILY_LIMIT_EXCEEDED",
            "Rejected for test",
            NOW,
            NOW.plus(TTL));
    order.verify(transactions).commit(any());
    verify(store, never()).complete(any(), any(), any(), anyInt(), any());
  }

  @Test
  void everyCodeOfTheConfiguredSetIsStoredRiskBlockedIncluded() {
    insertReturns(true);
    assertThat(engine.storedRejections()).isEqualTo(TRANSFER_REJECTIONS);
    for (ErrorCode code : TRANSFER_REJECTIONS) {
      assertThatThrownBy(() -> engine.execute(request, rejecting(code)))
          .satisfies(e -> assertCode(e, code));
      verify(store)
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
    // RISK_BLOCKED is stored with its own status, 403.
    verify(store)
        .insertIfAbsent(
            any(),
            anyString(),
            any(),
            anyString(),
            eq(403),
            eq("RISK_BLOCKED"),
            any(),
            any(),
            any());
  }

  @Test
  void aCodeOutsideTheConfiguredSetIsNotStoredRiskBlockedForTheWalletSet() {
    IdempotencyEngine wallet = engine(WALLET_REJECTIONS);
    insertReturns(true);

    assertThatThrownBy(() -> wallet.execute(request, rejecting(ErrorCode.RISK_BLOCKED)))
        .satisfies(e -> assertCode(e, ErrorCode.RISK_BLOCKED));

    verify(store, times(1))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    verify(transactions).rollback(any());
    verify(transactions, never()).commit(any());
  }

  /** Q3, Q6: a risk failure or a timeout is SYSTEM_ERROR, and SYSTEM_ERROR is never stored. */
  @Test
  void systemErrorIsNeverStored() {
    insertReturns(true);
    List<ErrorCode> hooked = new ArrayList<>();

    assertThatThrownBy(
            () ->
                engine.execute(
                    request, rejecting(ErrorCode.SYSTEM_ERROR), e -> hooked.add(e.errorCode())))
        .satisfies(e -> assertCode(e, ErrorCode.SYSTEM_ERROR));

    assertThat(hooked).isEmpty();
    verify(store, never())
        .insertIfAbsent(
            any(), anyString(), any(), anyString(), eq(500), any(), any(), any(), any());
    verify(transactions, never()).commit(any());
  }

  // The refusal hook (W5) ------------------------------------------------------------------------

  @Test
  void theRefusalHookRunsOnceInTheTransactionThatStoresTheRefusal() {
    insertReturns(true, true);
    List<ErrorCode> hooked = new ArrayList<>();

    assertThatThrownBy(
            () ->
                engine.execute(
                    request, rejecting(ErrorCode.RISK_BLOCKED), e -> hooked.add(e.errorCode())))
        .isInstanceOf(ApiException.class);

    assertThat(hooked).containsExactly(ErrorCode.RISK_BLOCKED);
    // Rolled back money transaction, then the committed refusal transaction.
    verify(transactions).rollback(any());
    verify(transactions).commit(any());
  }

  @Test
  void theRefusalHookDoesNotRunForAReplayARaceOrAnUnstoredError() {
    List<ErrorCode> hooked = new ArrayList<>();

    // Replay of a stored refusal.
    insertReturns(false);
    existing(rejection(HASH, ErrorCode.INSUFFICIENT_BALANCE));
    assertThatThrownBy(() -> engine.execute(request, succeeding(), e -> hooked.add(e.errorCode())))
        .isInstanceOf(ApiException.class);

    // Another request with the same key committed between rollback and storage.
    insertReturns(true, false);
    existing(success(HASH, NOW.plus(TTL)));
    engine.execute(
        request, rejecting(ErrorCode.INSUFFICIENT_BALANCE), e -> hooked.add(e.errorCode()));

    // Not a stored rejection.
    insertReturns(true);
    assertThatThrownBy(
            () ->
                engine.execute(
                    request, rejecting(ErrorCode.KYC_REQUIRED), e -> hooked.add(e.errorCode())))
        .isInstanceOf(ApiException.class);

    assertThat(hooked).isEmpty();
  }

  @Test
  void aFailingRefusalHookStoresNothing() {
    insertReturns(true, true);

    assertThatThrownBy(
            () ->
                engine.execute(
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
    existing(success(HASH, NOW.plus(TTL)));

    IdempotentResult result = engine.execute(request, rejecting(ErrorCode.INSUFFICIENT_BALANCE));

    assertThat(result.replayed()).isTrue();
    verify(store, never()).replace(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aConcurrentRequestWithAnotherBodyMakesTheRejectionKeyReused() {
    insertReturns(true, false);
    existing(success(OTHER_HASH, NOW.plus(TTL)));

    assertThatThrownBy(() -> engine.execute(request, rejecting(ErrorCode.INSUFFICIENT_BALANCE)))
        .satisfies(e -> assertCode(e, ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @Test
  void aRejectionReplacesAnExpiredRecordFoundInItsOwnTransaction() {
    insertReturns(true, false);
    existing(success(OTHER_HASH, NOW.minusSeconds(1)));

    assertThatThrownBy(() -> engine.execute(request, rejecting(ErrorCode.WALLET_FROZEN)))
        .satisfies(e -> assertCode(e, ErrorCode.WALLET_FROZEN));
    verify(store)
        .replace(
            CUSTOMER,
            OPERATION,
            KEY,
            HASH,
            409,
            "WALLET_FROZEN",
            "Rejected for test",
            NOW,
            NOW.plus(TTL));
  }

  @Test
  void otherApiErrorsAreRethrownAndNotStored() {
    insertReturns(true);

    assertThatThrownBy(() -> engine.execute(request, rejecting(ErrorCode.KYC_REQUIRED)))
        .satisfies(e -> assertCode(e, ErrorCode.KYC_REQUIRED));
    verify(store, times(1))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    verify(transactions).rollback(any());
    verify(transactions, never()).commit(any());
  }

  @Test
  void unexpectedFailuresAreRethrownAndNotStored() {
    insertReturns(true);

    assertThatThrownBy(
            () ->
                engine.execute(
                    request,
                    () -> {
                      throw new IllegalStateException("posting failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("posting failed");
    verify(store, times(1))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    verify(transactions, never()).commit(any());
  }

  @Test
  void aMovementWithoutResponseIsAnInternalError() {
    insertReturns(true);

    assertThatThrownBy(() -> engine.execute(request, () -> null))
        .isInstanceOf(NullPointerException.class);
    verify(transactions, never()).commit(any());
  }

  // Early replay (Q3 step 4) ---------------------------------------------------------------------

  @Test
  void earlyReplayOfNothingStoredIsEmpty() {
    doReturn(Optional.empty()).when(store).find(CUSTOMER, OPERATION, KEY);

    assertThat(engine.replayIfCompleted(request)).isEmpty();
  }

  @Test
  void earlyReplayReturnsAStoredSuccessAsReplayed() {
    committed(success(HASH, NOW.plus(TTL)));

    assertThat(engine.replayIfCompleted(request))
        .contains(new IdempotentResult(new StoredResponse(201, "{\"id\": \"t-1\"}"), true));
  }

  @Test
  void earlyReplayThrowsAStoredRejectionAgain() {
    committed(rejection(HASH, ErrorCode.RISK_BLOCKED));

    assertThatThrownBy(() -> engine.replayIfCompleted(request))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RISK_BLOCKED);
              assertThat(e.getMessage()).isEqualTo("Rejected earlier");
            });
  }

  @Test
  void earlyReplayWithAnotherBodyIsKeyReusedForASuccessOrARejection() {
    committed(success(OTHER_HASH, NOW.plus(TTL)));
    assertThatThrownBy(() -> engine.replayIfCompleted(request))
        .satisfies(e -> assertCode(e, ErrorCode.IDEMPOTENCY_KEY_REUSED));

    committed(rejection(OTHER_HASH, ErrorCode.INSUFFICIENT_BALANCE));
    assertThatThrownBy(() -> engine.replayIfCompleted(request))
        .satisfies(e -> assertCode(e, ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @Test
  void earlyReplayTreatsAnExpiredRecordAsAbsentByTheApplicationClock() {
    committed(success(HASH, NOW));
    assertThat(engine.replayIfCompleted(request)).isEmpty();

    // Another hash, expired: also absent, not a reuse.
    committed(success(OTHER_HASH, NOW.minusSeconds(1)));
    assertThat(engine.replayIfCompleted(request)).isEmpty();

    // One microsecond before expiry it still counts.
    committed(success(HASH, NOW.plusNanos(1_000)));
    assertThat(engine.replayIfCompleted(request)).isPresent();
  }

  @Test
  void earlyReplayTreatsARecordWithoutOutcomeAsAbsent() {
    committed(new Row(OTHER_HASH, null, null, null, null, NOW.plus(TTL)));

    assertThat(engine.replayIfCompleted(request)).isEmpty();
  }

  @Test
  void earlyReplayWritesNothingLocksNothingAndOpensNoTransaction() {
    committed(success(HASH, NOW.plus(TTL)));

    engine.replayIfCompleted(request);

    verify(store).find(CUSTOMER, OPERATION, KEY);
    verify(store, never()).findForUpdate(any(), any(), any());
    verify(store, never())
        .insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(store, never()).replace(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(store, never()).complete(any(), any(), any(), anyInt(), any());
    verifyNoInteractions(transactions);
  }

  @Test
  void earlyReplayOfAStoredRejectionWithoutCodeIsAnInternalError() {
    committed(new Row(HASH, 403, "{}", null, null, NOW.plus(TTL)));

    assertThatThrownBy(() -> engine.replayIfCompleted(request))
        .isInstanceOf(IllegalStateException.class);
  }

  // Preconditions ------------------------------------------------------------------------------

  @Test
  void refusesToJoinAnExistingTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> engine.execute(request, succeeding()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> engine.replayIfCompleted(request))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verify(transactions, never()).getTransaction(any());
    verifyNoInteractions(store);
  }

  @Test
  void requiresRequestMovementAndHook() {
    assertThatThrownBy(() -> engine.execute(null, succeeding()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> engine.execute(request, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> engine.execute(request, succeeding(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> engine.replayIfCompleted(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theConfiguredSetIsCopiedAndCannotBeChanged() {
    Set<ErrorCode> configured = EnumSet.of(ErrorCode.INSUFFICIENT_BALANCE);
    IdempotencyEngine built = engine(configured);
    configured.add(ErrorCode.RISK_BLOCKED);

    assertThat(built.storedRejections()).containsExactly(ErrorCode.INSUFFICIENT_BALANCE);
    assertThatThrownBy(() -> built.storedRejections().add(ErrorCode.WALLET_FROZEN))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(built.ttl()).isEqualTo(TTL);
  }

  /** Q3: the allow-list is exactly the business-rule refusals of a money movement. */
  @Test
  void theStorableCodesAreAnExplicitAllowList() {
    assertThat(IdempotencyEngine.STORABLE_REJECTIONS)
        .containsExactlyInAnyOrder(
            ErrorCode.INSUFFICIENT_BALANCE,
            ErrorCode.SINGLE_LIMIT_EXCEEDED,
            ErrorCode.DAILY_LIMIT_EXCEEDED,
            ErrorCode.WALLET_FROZEN,
            ErrorCode.RISK_BLOCKED);
    assertThatThrownBy(() -> IdempotencyEngine.STORABLE_REJECTIONS.add(ErrorCode.NOT_FOUND))
        .isInstanceOf(UnsupportedOperationException.class);
    // Both module sets are accepted as they are.
    assertThat(engine(TRANSFER_REJECTIONS).storedRejections()).isEqualTo(TRANSFER_REJECTIONS);
    assertThat(engine(WALLET_REJECTIONS).storedRejections()).isEqualTo(WALLET_REJECTIONS);
  }

  /** Every other code is refused at construction, alone or next to an allowed one. */
  @ParameterizedTest
  @EnumSource(
      value = ErrorCode.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {
        "INSUFFICIENT_BALANCE",
        "SINGLE_LIMIT_EXCEEDED",
        "DAILY_LIMIT_EXCEEDED",
        "WALLET_FROZEN",
        "RISK_BLOCKED"
      })
  void refusesASetWithACodeOutsideTheAllowList(ErrorCode code) {
    assertThatThrownBy(() -> engine(EnumSet.of(ErrorCode.INSUFFICIENT_BALANCE, code)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("only business-rule refusals can be stored");
    assertThatThrownBy(() -> engine(EnumSet.of(code))).isInstanceOf(IllegalArgumentException.class);
  }

  // Transaction preparer (Q6, D14) ---------------------------------------------------------------

  @Test
  void thePreparerRunsFirstInTheMoneyTransactionBeforeTheIdempotencyRow() {
    insertReturns(true);

    engine.execute(request, succeeding());

    InOrder order = inOrder(transactions, preparer, store);
    order.verify(transactions).getTransaction(any());
    order.verify(preparer).run();
    order
        .verify(store)
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    order.verify(store).complete(any(), any(), any(), anyInt(), any());
    order.verify(transactions).commit(any());
    verify(preparer, times(1)).run();
  }

  @Test
  void thePreparerAlsoRunsFirstInTheRejectionsOwnTransaction() {
    insertReturns(true, true);

    assertThatThrownBy(() -> engine.execute(request, rejecting(ErrorCode.RISK_BLOCKED)))
        .satisfies(e -> assertCode(e, ErrorCode.RISK_BLOCKED));

    InOrder order = inOrder(transactions, preparer, store);
    // The money transaction.
    order.verify(transactions).getTransaction(any());
    order.verify(preparer).run();
    order
        .verify(store)
        .insertIfAbsent(
            any(), anyString(), any(), anyString(), eq(null), any(), any(), any(), any());
    order.verify(transactions).rollback(any());
    // The rejection's own transaction.
    order.verify(transactions).getTransaction(any());
    order.verify(preparer).run();
    order
        .verify(store)
        .insertIfAbsent(
            any(), anyString(), any(), anyString(), eq(403), any(), any(), any(), any());
    order.verify(transactions).commit(any());
    verify(preparer, times(2)).run();
  }

  @Test
  void aFailingPreparerClaimsNothingAndIsNotStored() {
    RuntimeException failure = new IllegalStateException("SET LOCAL failed");
    org.mockito.Mockito.doThrow(failure).when(preparer).run();

    assertThatThrownBy(() -> engine.execute(request, succeeding())).isSameAs(failure);

    assertThat(movements).hasValue(0);
    verifyNoInteractions(store);
    verify(transactions).rollback(any());
    verify(transactions, never()).commit(any());
  }

  @Test
  void aPreparerFailingInTheRejectionsTransactionStoresNothing() {
    insertReturns(true);
    RuntimeException failure = new IllegalStateException("SET LOCAL failed");
    // Succeeds in the money transaction, fails in the rejection's own one.
    org.mockito.Mockito.doNothing().doThrow(failure).when(preparer).run();
    List<ErrorCode> hooked = new ArrayList<>();

    assertThatThrownBy(
            () ->
                engine.execute(
                    request,
                    rejecting(ErrorCode.INSUFFICIENT_BALANCE),
                    e -> hooked.add(e.errorCode())))
        .isSameAs(failure);

    assertThat(hooked).isEmpty();
    // Only the claim of the money transaction; no rejection insert.
    verify(store, times(1))
        .insertIfAbsent(any(), anyString(), any(), anyString(), any(), any(), any(), any(), any());
    verify(store, never())
        .insertIfAbsent(
            any(), anyString(), any(), anyString(), eq(409), any(), any(), any(), any());
    verify(transactions, times(2)).rollback(any());
    verify(transactions, never()).commit(any());
  }

  @Test
  void theEarlyReplayDoesNotRunThePreparer() {
    committed(success(HASH, NOW.plus(TTL)));

    engine.replayIfCompleted(request);

    verifyNoInteractions(preparer);
  }

  @Test
  void aPreparerIsRequired() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    assertThatThrownBy(
            () -> new IdempotencyEngine(store, TTL, WALLET_REJECTIONS, clock, transactions, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void refusesAnInvalidConfiguration() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    assertThatThrownBy(() -> engine(Set.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new IdempotencyEngine(
                    store,
                    Duration.ofMillis(999),
                    WALLET_REJECTIONS,
                    clock,
                    transactions,
                    preparer))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IdempotencyEngine(null, TTL, WALLET_REJECTIONS, clock, transactions, preparer))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new IdempotencyEngine(store, TTL, WALLET_REJECTIONS, null, transactions, preparer))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new IdempotencyEngine(store, TTL, WALLET_REJECTIONS, clock, null, preparer))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void keyReusedIsTheDocumentedConflict() {
    ApiException e = IdempotencyEngine.keyReused();

    assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertThat(e.errorCode().status().value()).isEqualTo(409);
    assertThat(e.getMessage()).isEqualTo(IdempotencyEngine.KEY_REUSED);
  }
}
