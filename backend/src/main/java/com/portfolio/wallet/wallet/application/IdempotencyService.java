package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.idempotency.IdempotencyEngine;
import com.portfolio.wallet.wallet.domain.MovementIdentity;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code Idempotency-Key} handling for deposits and withdrawals (documentations/02 §1.4; doc 06
 * MVP-ADR-03, -06, -08; doc 07 §7, §8, D02–D05; doc 08 §4B.2 Q14 and owner requirements 1 and 2).
 *
 * <p>The wallet module's engine: {@link IdempotencyEngine} (flow, rejections, expiry, lock order;
 * see its documentation) over the wallet's own store, {@link IdempotencyRecordRepository} on {@code
 * wallet.idempotency_records}, with the wallet's TTL ({@link IdempotencyProperties}) and the
 * wallet's stored rejections, {@link #STORED_REJECTIONS} (doc 06 MVP-ADR-06 "Transfers and one
 * engine": shared code, not shared records). This class adapts the wallet's request and response
 * types and adds the Q14 check of {@link #requireSameMovement}. Both transactions the engine opens
 * start with {@link TransactionTimeouts#apply()} (doc 07 D14), so a wait for the idempotency row,
 * the wallet or the usage row ends within the lock timeout as {@code 500 SYSTEM_ERROR}, and is not
 * stored for the key.
 *
 * <p>Nothing here is logged: the key, the hash and the bodies stay out of the logs.
 */
@Service
public class IdempotencyService {

  /** Business-rule rejections that are stored and replayed (doc 02 §1.4; doc 07 D05). */
  static final Set<ErrorCode> STORED_REJECTIONS =
      EnumSet.of(
          ErrorCode.INSUFFICIENT_BALANCE,
          ErrorCode.SINGLE_LIMIT_EXCEEDED,
          ErrorCode.DAILY_LIMIT_EXCEEDED,
          ErrorCode.WALLET_FROZEN);

  static final String KEY_REUSED = IdempotencyEngine.KEY_REUSED;

  /** Insert attempts when the row disappears between insert and lock; one purge race at most. */
  static final int MAX_CLAIM_ATTEMPTS = IdempotencyEngine.MAX_CLAIM_ATTEMPTS;

  private final IdempotencyEngine engine;

  public IdempotencyService(
      IdempotencyRecordRepository records,
      IdempotencyProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager,
      TransactionTimeouts timeouts) {
    Objects.requireNonNull(timeouts, "timeouts");
    this.engine =
        new IdempotencyEngine(
            records,
            properties.ttl(),
            STORED_REJECTIONS,
            clock,
            transactionManager,
            // D14: lock_timeout and statement_timeout, before the idempotency row is locked (doc 06
            // "Lock and statement timeouts"; doc 08 §4C.2 Q6).
            timeouts::apply);
  }

  /**
   * Runs {@code movement} at most once per key and request ({@link IdempotencyEngine#execute}).
   *
   * @param movement the money movement; it runs inside the transaction that holds the idempotency
   *     row, takes its own locks after it, and returns the success response. It signals a business
   *     rejection by throwing an {@link ApiException} with a code in {@link #STORED_REJECTIONS}
   * @throws ApiException {@code IDEMPOTENCY_KEY_REUSED}, a fresh or replayed stored rejection, or
   *     whatever else the movement throws
   * @throws IllegalStateException if called inside a transaction
   */
  public IdempotentResult execute(IdempotentRequest request, Supplier<StoredResponse> movement) {
    return execute(request, movement, refusal -> {});
  }

  /**
   * As {@link #execute(IdempotentRequest, Supplier)}, and {@code onRefusalStored} runs inside the
   * transaction that stores a business rejection for this request, after the record is written. It
   * runs only when this request's rejection is what got stored: not for a replay, and not when a
   * concurrent request with the same key committed first (owner, 2026-11-04, W5: the refusal's
   * audit row exists exactly when the refusal is stored). If it throws, nothing is stored.
   */
  public IdempotentResult execute(
      IdempotentRequest request,
      Supplier<StoredResponse> movement,
      Consumer<ApiException> onRefusalStored) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(movement, "movement");
    Objects.requireNonNull(onRefusalStored, "onRefusalStored");
    com.portfolio.wallet.common.idempotency.IdempotentResult result =
        engine.execute(
            request.toEngineRequest(),
            () -> {
              StoredResponse response = Objects.requireNonNull(movement.get(), "movement response");
              return new com.portfolio.wallet.common.idempotency.StoredResponse(
                  response.status(), response.jsonBody());
            },
            onRefusalStored);
    return new IdempotentResult(
        new StoredResponse(result.response().status(), result.response().jsonBody()),
        result.replayed());
  }

  /**
   * Q14 hook for the movement: when no idempotency record was found but a deposit or withdrawal row
   * with the same {@code (customer, key)} exists, the movement compares it before returning it.
   *
   * @throws ApiException {@code 409 IDEMPOTENCY_KEY_REUSED} unless customer, operation, wallet,
   *     amount and currency all match
   */
  public static void requireSameMovement(MovementIdentity requested, MovementIdentity stored) {
    if (!requested.isSameMovementAs(stored)) {
      throw IdempotencyEngine.keyReused();
    }
  }
}
