package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.wallet.domain.MovementIdentity;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRow;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code Idempotency-Key} handling for deposits and withdrawals (documentations/02 §1.4; doc 06
 * MVP-ADR-03, -06, -08; doc 07 §7, §8, D02–D05; doc 08 §4B.2 Q14 and owner requirements 1 and 2).
 *
 * <h2>Flow</h2>
 *
 * {@link #execute} opens the money transaction itself, so the idempotency row is always its
 * <b>first lock</b> (doc 07 §8 lock order: idempotency row, then wallets ascending in one
 * statement, then {@code daily_limit_usage}, then ledger accounts through the entry trigger). It
 * refuses to run inside an existing transaction, which could already hold later locks.
 *
 * <ol>
 *   <li><b>Insert or lock:</b> {@code INSERT … ON CONFLICT DO NOTHING}, then, if the row existed,
 *       {@code SELECT … FOR UPDATE}. A concurrent duplicate waits on the primary key or the row
 *       lock until the first transaction ends, then sees its committed outcome. If the row vanished
 *       between the two statements (the purge), the insert is tried again, a bounded number of
 *       times.
 *   <li><b>New key</b> (inserted, or an existing row replaced as below): the movement runs in the
 *       same transaction, and its success response (status and body) is stored on the row before
 *       commit. Record and movement become visible together, or not at all.
 *   <li><b>Same key, same request hash:</b> the stored outcome is replayed: a success as {@link
 *       IdempotentResult#replayed()}, a stored rejection by throwing its {@link ApiException}
 *       again. Nothing is written and no money moves.
 *   <li><b>Same key, other hash:</b> {@code 409 IDEMPOTENCY_KEY_REUSED}; nothing is written.
 * </ol>
 *
 * <h2>Rejections (D05; owner requirement 2)</h2>
 *
 * A business rejection is an {@link ApiException} with one of {@link #STORED_REJECTIONS} thrown by
 * the movement. The money transaction rolls back, the claimed row included, so a failed first
 * request never leaves a row behind. Then a <b>second, separate transaction</b> stores the
 * rejection for the key with the same insert-or-lock step and commits it, and only then is the
 * rejection thrown. If a concurrent request with the same key committed in between, its outcome
 * wins: a same-hash success or rejection is replayed, another hash gives {@code 409}. Any other
 * exception (validation, {@code 5xx}, a database failure, a rejection code outside the set) is
 * rethrown and leaves no row, so a retry runs fresh.
 *
 * <h2>Expiry (D04)</h2>
 *
 * A key is retained for {@link IdempotencyProperties#ttl()} (24 h) after its row was written. A row
 * whose {@code expires_at} has passed by the application {@link Clock} counts as absent, exactly as
 * after the purge, so the outcome never depends on when the purge last ran: under its lock it is
 * replaced by the new request's row. After that, a deposit or withdrawal row with the same {@code
 * (customer, key)} remains the final guard ({@link #requireSameMovement}, Q14). A committed row
 * without an outcome cannot be produced by this class; one is treated as absent too, so it cannot
 * block a retry.
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

  static final String KEY_REUSED = "Idempotency-Key was already used for a different request";

  /** Insert attempts when the row disappears between insert and lock; one purge race at most. */
  static final int MAX_CLAIM_ATTEMPTS = 3;

  private final IdempotencyRecordRepository records;
  private final IdempotencyProperties properties;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public IdempotencyService(
      IdempotencyRecordRepository records,
      IdempotencyProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.records = records;
    this.properties = properties;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Runs {@code movement} at most once per key and request, as described on the class.
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
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "an idempotent movement starts its own transaction: the idempotency row is its first lock");
    }
    Outcome outcome;
    try {
      outcome = Objects.requireNonNull(transactions.execute(status -> runOnce(request, movement)));
    } catch (Rejected rejected) {
      // The money transaction has rolled back; store the rejection in a transaction of its own.
      ApiException rejection = rejected.rejection;
      outcome =
          Objects.requireNonNull(
              transactions.execute(status -> storeRejection(request, rejection, onRefusalStored)));
    }
    return outcome.toResult();
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
      throw keyReused();
    }
  }

  // The money transaction ------------------------------------------------------------------------

  private Outcome runOnce(IdempotentRequest request, Supplier<StoredResponse> movement) {
    Optional<Outcome> earlier = claim(request, null);
    if (earlier.isPresent()) {
      return earlier.get();
    }
    StoredResponse response;
    try {
      response = Objects.requireNonNull(movement.get(), "movement response");
    } catch (ApiException e) {
      if (STORED_REJECTIONS.contains(e.errorCode())) {
        throw new Rejected(e);
      }
      throw e;
    }
    records.complete(
        request.customerId(),
        request.operation(),
        request.key(),
        response.status(),
        response.jsonBody());
    return new Outcome.Success(response, false);
  }

  // The rejection's own transaction
  // ----------------------------------------------------------------

  private Outcome storeRejection(
      IdempotentRequest request, ApiException rejection, Consumer<ApiException> onRefusalStored) {
    Optional<Outcome> earlier = claim(request, rejection);
    if (earlier.isPresent()) {
      return earlier.get();
    }
    onRefusalStored.accept(rejection);
    return new Outcome.Rejection(rejection.errorCode(), rejection.getMessage());
  }

  // Insert or lock -------------------------------------------------------------------------------

  /**
   * Takes the row for the request: inserts it, or locks the existing one and replaces it if it no
   * longer counts.
   *
   * @param rejection null to claim the key for a movement (no outcome yet); otherwise the rejection
   *     to store as the outcome
   * @return empty if this request now owns the row; otherwise the outcome of an earlier request
   */
  private Optional<Outcome> claim(IdempotentRequest request, ApiException rejection) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(properties.ttl());
    Integer status = rejection == null ? null : rejection.errorCode().status().value();
    String code = rejection == null ? null : rejection.errorCode().name();
    String message = rejection == null ? null : rejection.getMessage();
    for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
      if (records.insertIfAbsent(
          request.customerId(),
          request.operation(),
          request.key(),
          request.requestHash(),
          status,
          code,
          message,
          now,
          expiresAt)) {
        return Optional.empty();
      }
      Optional<IdempotencyRecordRow> locked =
          records.findForUpdate(request.customerId(), request.operation(), request.key());
      if (locked.isEmpty()) {
        continue;
      }
      IdempotencyRecordRow row = locked.get();
      if (!now.isBefore(row.expiresAt()) || row.responseStatus() == null) {
        records.replace(
            request.customerId(),
            request.operation(),
            request.key(),
            request.requestHash(),
            status,
            code,
            message,
            now,
            expiresAt);
        return Optional.empty();
      }
      if (!row.requestHash().equals(request.requestHash())) {
        return Optional.of(Outcome.KeyReused.INSTANCE);
      }
      return Optional.of(replay(row));
    }
    throw new IllegalStateException("idempotency record could not be claimed");
  }

  private static Outcome replay(IdempotencyRecordRow row) {
    int status = row.responseStatus();
    if (status >= 200 && status <= 299) {
      return new Outcome.Success(new StoredResponse(status, row.responseBody()), true);
    }
    if (row.errorCode() == null || row.errorMessage() == null) {
      throw new IllegalStateException("stored rejection has no error code or message");
    }
    return new Outcome.Rejection(ErrorCode.valueOf(row.errorCode()), row.errorMessage());
  }

  private static ApiException keyReused() {
    return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, KEY_REUSED);
  }

  /** Carries a business rejection out of the money transaction, which makes it roll back. */
  private static final class Rejected extends RuntimeException {

    private final transient ApiException rejection;

    Rejected(ApiException rejection) {
      super(null, null, false, false);
      this.rejection = rejection;
    }
  }

  /** What a request ends with; turned into a result or an exception only after commit. */
  private sealed interface Outcome {

    IdempotentResult toResult();

    record Success(StoredResponse response, boolean replayed) implements Outcome {
      @Override
      public IdempotentResult toResult() {
        return new IdempotentResult(response, replayed);
      }
    }

    record Rejection(ErrorCode code, String message) implements Outcome {
      @Override
      public IdempotentResult toResult() {
        throw new ApiException(code, message);
      }
    }

    enum KeyReused implements Outcome {
      INSTANCE;

      @Override
      public IdempotentResult toResult() {
        throw keyReused();
      }
    }
  }
}
