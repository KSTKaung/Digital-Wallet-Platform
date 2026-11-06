package com.portfolio.wallet.common.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The port through which {@link IdempotencyEngine} and {@link IdempotencyPurge} reach one module's
 * idempotency table (documentations/06 MVP-ADR-06 "Transfers and one engine"; doc 08 §4C.2 Q2).
 * Each money module implements it in its own {@code persistence} package over its own table, keyed
 * by {@code (customer_id, operation, idempotency_key)}; no implementation reads or writes another
 * module's table.
 *
 * <p>Implementations bind times exactly as given: they come from the application's {@code Clock},
 * so the purge and the expiry check in a money transaction measure against the same time source. A
 * rejection is stored as {@code {"errorCode": …, "message": …}}, a success as the body the engine
 * supplies.
 */
public interface IdempotencyStore {

  /**
   * Inserts the record unless one exists for the key. If another open transaction has inserted the
   * same key, this waits for it to end (the primary key serialises concurrent duplicates).
   *
   * @param status null for a claim whose outcome follows in the same transaction; otherwise the
   *     stored rejection's status, with {@code errorCode} and {@code message}
   * @return true if this call inserted the row
   */
  boolean insertIfAbsent(
      UUID customerId,
      String operation,
      UUID key,
      String requestHash,
      Integer status,
      String errorCode,
      String message,
      Instant createdAt,
      Instant expiresAt);

  /**
   * Locks and reads the committed record; waits while another transaction holds it.
   *
   * @return empty if there is none, for example because the purge deleted it meanwhile
   */
  Optional<? extends IdempotencyRecord> findForUpdate(UUID customerId, String operation, UUID key);

  /**
   * Reads the committed record without locking it, and without waiting for an open transaction that
   * holds it (for {@link IdempotencyEngine#replayIfCompleted}).
   *
   * @return empty if there is none, or if it is not committed yet
   */
  Optional<? extends IdempotencyRecord> find(UUID customerId, String operation, UUID key);

  /**
   * Replaces a locked record that no longer counts (expired, or without an outcome) by a new one;
   * parameters as {@link #insertIfAbsent}.
   *
   * @throws IllegalStateException if no row was updated
   */
  void replace(
      UUID customerId,
      String operation,
      UUID key,
      String requestHash,
      Integer status,
      String errorCode,
      String message,
      Instant createdAt,
      Instant expiresAt);

  /**
   * Stores the success outcome on the record this transaction claimed.
   *
   * @throws IllegalStateException if no row without an outcome was updated
   */
  void complete(UUID customerId, String operation, UUID key, int status, String jsonBody);

  /**
   * Deletes at most {@code limit} records that expired at or before {@code now}, in one statement.
   * Rows locked by a money transaction are skipped ({@code SKIP LOCKED}), so the purge never waits
   * on, or blocks, a live movement; a skipped row is deleted by a later run if it is still expired.
   */
  int deleteExpired(Instant now, int limit);
}
