package com.portfolio.wallet.common.idempotency;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code Idempotency-Key} handling for money POSTs (documentations/02 §1.4; doc 06 MVP-ADR-03, -06,
 * -08; doc 07 §7, §8, D02–D05; doc 08 §4B.2 Q14 and owner requirements 1 and 2, §4C.2 Q2, Q3, Q6).
 *
 * <p>A plain class, not a bean: each money module builds its own engine over <b>its own</b> {@link
 * IdempotencyStore}, with its own TTL and its own set of stored rejection codes (doc 06 MVP-ADR-06
 * "Transfers and one engine": shared code, not shared records).
 *
 * <h2>Flow</h2>
 *
 * {@link #execute} opens the money transaction itself, so the idempotency row is always its
 * <b>first lock</b> (doc 07 §8 lock order: idempotency row, then wallets ascending in one
 * statement, then {@code daily_limit_usage}, then ledger accounts through the entry trigger). It
 * refuses to run inside an existing transaction, which could already hold later locks. The module's
 * transaction preparer runs first in each transaction the engine opens, before that lock, so
 * per-transaction settings such as {@code SET LOCAL lock_timeout} bound every wait (Q6).
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
 * A business rejection is an {@link ApiException} with one of {@link #storedRejections()} thrown by
 * the movement. The money transaction rolls back, the claimed row included, so a failed first
 * request never leaves a row behind. Then a <b>second, separate transaction</b> stores the
 * rejection for the key with the same insert-or-lock step and commits it, and only then is the
 * rejection thrown. If a concurrent request with the same key committed in between, its outcome
 * wins: a same-hash success or rejection is replayed, another hash gives {@code 409}. Any other
 * exception (validation, {@code 5xx} such as {@code SYSTEM_ERROR} for a risk failure or a lock or
 * statement timeout, a database failure, a rejection code outside the set) is rethrown and leaves
 * no row, so a retry runs fresh. A set may only hold codes of {@link #STORABLE_REJECTIONS}.
 *
 * <h2>Expiry (D04)</h2>
 *
 * A key is retained for the engine's TTL (24 h) after its row was written. A row whose {@code
 * expires_at} has passed by the application {@link Clock} counts as absent, exactly as after the
 * purge, so the outcome never depends on when the purge last ran: under its lock it is replaced by
 * the new request's row. After that, the module's business row with the same {@code (customer,
 * key)} remains the final guard (for deposits and withdrawals, Q14). A committed row without an
 * outcome cannot be produced by this class; one is treated as absent too, so it cannot block a
 * retry.
 *
 * <h2>Early replay (Q3 step 4)</h2>
 *
 * {@link #replayIfCompleted} reads the committed row without a lock, so a caller can answer a
 * repeated request before work that must not be repeated (the transfer pre-check and risk
 * assessment). It is only a shortcut: {@link #execute} stays the authority under the lock, and a
 * concurrent request may still win there.
 *
 * <p>Nothing here is logged: the key, the hash and the bodies stay out of the logs.
 */
public final class IdempotencyEngine {

  /** The message of {@code 409 IDEMPOTENCY_KEY_REUSED}. */
  public static final String KEY_REUSED =
      "Idempotency-Key was already used for a different request";

  /** Insert attempts when the row disappears between insert and lock; one purge race at most. */
  public static final int MAX_CLAIM_ATTEMPTS = 3;

  /**
   * Every code an engine may be configured to store (doc 02 §1.4; doc 06 MVP-ADR-06; doc 08 §4C.2
   * Q3): the business-rule refusals of a money movement. Anything else (validation, {@code
   * NOT_FOUND}, {@code KYC_REQUIRED}, {@code SYSTEM_ERROR}, …) is refused at construction, so a
   * module cannot store it by mistake.
   */
  public static final Set<ErrorCode> STORABLE_REJECTIONS =
      Collections.unmodifiableSet(
          EnumSet.of(
              ErrorCode.INSUFFICIENT_BALANCE,
              ErrorCode.SINGLE_LIMIT_EXCEEDED,
              ErrorCode.DAILY_LIMIT_EXCEEDED,
              ErrorCode.WALLET_FROZEN,
              ErrorCode.RISK_BLOCKED));

  private final IdempotencyStore store;
  private final Duration ttl;
  private final Set<ErrorCode> storedRejections;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final Runnable transactionPreparer;

  /**
   * @param store the module's own store
   * @param ttl how long a key is retained; 1 second to 30 days ({@link IdempotencySettings})
   * @param storedRejections the business rejections that are stored and replayed: at least one,
   *     each in {@link #STORABLE_REJECTIONS}
   * @param transactionPreparer runs as the <b>first action</b> inside both transactions this engine
   *     opens (the money transaction and the rejection's own one), before any store call and so
   *     before the idempotency row is locked: the place for {@code SET LOCAL lock_timeout} and
   *     {@code statement_timeout} (doc 06 "Lock and statement timeouts"; doc 07 D14; Q6). If it
   *     throws, that transaction rolls back, nothing is claimed or stored, and the exception
   *     propagates. A module without such settings passes a no-op
   * @throws IllegalArgumentException if the TTL or the set is invalid
   */
  public IdempotencyEngine(
      IdempotencyStore store,
      Duration ttl,
      Set<ErrorCode> storedRejections,
      Clock clock,
      PlatformTransactionManager transactionManager,
      Runnable transactionPreparer) {
    this.store = Objects.requireNonNull(store, "store");
    this.ttl = IdempotencySettings.requireValidTtl("idempotency.", ttl);
    this.storedRejections = validRejections(storedRejections);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactionPreparer = Objects.requireNonNull(transactionPreparer, "transactionPreparer");
  }

  private static Set<ErrorCode> validRejections(Set<ErrorCode> codes) {
    Objects.requireNonNull(codes, "storedRejections");
    if (codes.isEmpty()) {
      throw new IllegalArgumentException("at least one stored rejection code is required");
    }
    EnumSet<ErrorCode> copy = EnumSet.copyOf(codes);
    if (!STORABLE_REJECTIONS.containsAll(copy)) {
      throw new IllegalArgumentException("only business-rule refusals can be stored");
    }
    return Collections.unmodifiableSet(copy);
  }

  /** The business rejections this engine stores and replays. */
  public Set<ErrorCode> storedRejections() {
    return storedRejections;
  }

  /** How long a key is retained. */
  public Duration ttl() {
    return ttl;
  }

  /**
   * Runs {@code movement} at most once per key and request, as described on the class.
   *
   * @param movement the money movement; it runs inside the transaction that holds the idempotency
   *     row, takes its own locks after it, and returns the success response. It signals a business
   *     rejection by throwing an {@link ApiException} with a code in {@link #storedRejections()}
   * @throws ApiException {@code IDEMPOTENCY_KEY_REUSED}, a fresh or replayed stored rejection, or
   *     whatever else the movement throws
   * @throws IllegalStateException if called inside a transaction
   */
  public IdempotentResult execute(IdempotencyRequest request, Supplier<StoredResponse> movement) {
    return execute(request, movement, refusal -> {});
  }

  /**
   * As {@link #execute(IdempotencyRequest, Supplier)}, and {@code onRefusalStored} runs inside the
   * transaction that stores a business rejection for this request, after the record is written. It
   * runs only when this request's rejection is what got stored: not for a replay, and not when a
   * concurrent request with the same key committed first (owner, 2026-11-04, W5: the refusal's
   * audit row exists exactly when the refusal is stored). If it throws, nothing is stored.
   */
  public IdempotentResult execute(
      IdempotencyRequest request,
      Supplier<StoredResponse> movement,
      Consumer<ApiException> onRefusalStored) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(movement, "movement");
    Objects.requireNonNull(onRefusalStored, "onRefusalStored");
    requireNoTransaction();
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
   * Reads the committed record for the request without locking it (Q3 step 4) and, if it is
   * complete and still retained, answers as {@link #execute} would have: a same-hash success is
   * returned as replayed, a same-hash stored rejection is thrown again, another hash is {@code 409
   * IDEMPOTENCY_KEY_REUSED}. Nothing is written and nothing waits for another transaction.
   *
   * <p>Empty means "run the request": no record, an expired one (by the application {@link Clock}),
   * or one without an outcome. {@link #execute} must still follow, because it decides under the
   * lock; a concurrent request may have won by then.
   *
   * @throws ApiException {@code IDEMPOTENCY_KEY_REUSED} or the replayed stored rejection
   * @throws IllegalStateException if called inside a transaction
   */
  public Optional<IdempotentResult> replayIfCompleted(IdempotencyRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoTransaction();
    Optional<? extends IdempotencyRecord> found =
        store.find(request.customerId(), request.operation(), request.key());
    if (found.isEmpty()) {
      return Optional.empty();
    }
    IdempotencyRecord row = found.get();
    if (!counts(row, clock.instant())) {
      return Optional.empty();
    }
    if (!row.requestHash().equals(request.requestHash())) {
      throw keyReused();
    }
    return Optional.of(replay(row).toResult());
  }

  /** {@code 409 IDEMPOTENCY_KEY_REUSED} with {@link #KEY_REUSED}. */
  public static ApiException keyReused() {
    return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, KEY_REUSED);
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "an idempotent movement starts its own transaction: the idempotency row is its first lock");
    }
  }

  /** A record counts while it is retained and has an outcome. */
  private static boolean counts(IdempotencyRecord row, Instant now) {
    return now.isBefore(row.expiresAt()) && row.responseStatus() != null;
  }

  // The money transaction ------------------------------------------------------------------------

  private Outcome runOnce(IdempotencyRequest request, Supplier<StoredResponse> movement) {
    transactionPreparer.run();
    Optional<Outcome> earlier = claim(request, null);
    if (earlier.isPresent()) {
      return earlier.get();
    }
    StoredResponse response;
    try {
      response = Objects.requireNonNull(movement.get(), "movement response");
    } catch (ApiException e) {
      if (storedRejections.contains(e.errorCode())) {
        throw new Rejected(e);
      }
      throw e;
    }
    store.complete(
        request.customerId(),
        request.operation(),
        request.key(),
        response.status(),
        response.jsonBody());
    return new Outcome.Success(response, false);
  }

  // The rejection's own transaction --------------------------------------------------------------

  private Outcome storeRejection(
      IdempotencyRequest request, ApiException rejection, Consumer<ApiException> onRefusalStored) {
    transactionPreparer.run();
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
  private Optional<Outcome> claim(IdempotencyRequest request, ApiException rejection) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);
    Integer status = rejection == null ? null : rejection.errorCode().status().value();
    String code = rejection == null ? null : rejection.errorCode().name();
    String message = rejection == null ? null : rejection.getMessage();
    for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
      if (store.insertIfAbsent(
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
      Optional<? extends IdempotencyRecord> locked =
          store.findForUpdate(request.customerId(), request.operation(), request.key());
      if (locked.isEmpty()) {
        continue;
      }
      IdempotencyRecord row = locked.get();
      if (!counts(row, now)) {
        store.replace(
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

  private static Outcome replay(IdempotencyRecord row) {
    int status = row.responseStatus();
    if (status >= 200 && status <= 299) {
      return new Outcome.Success(new StoredResponse(status, row.responseBody()), true);
    }
    if (row.errorCode() == null || row.errorMessage() == null) {
      throw new IllegalStateException("stored rejection has no error code or message");
    }
    return new Outcome.Rejection(ErrorCode.valueOf(row.errorCode()), row.errorMessage());
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
