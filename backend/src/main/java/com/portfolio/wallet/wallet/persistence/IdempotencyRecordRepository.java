package com.portfolio.wallet.wallet.persistence;

import com.portfolio.wallet.common.idempotency.IdempotencyStore;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code wallet.idempotency_records} (V3; documentations/02 §1.4; doc 07 D02–D05). The key is
 * {@code (customer_id, operation, idempotency_key)}. Times come from the application's {@code
 * Clock}, so the purge and the expiry check in a money transaction measure against the same time
 * source. A rejection is stored as {@code {"errorCode": …, "message": …}}; a success as the body
 * the caller supplies.
 *
 * <p>The wallet module's implementation of the shared engine's port ({@link IdempotencyStore}; doc
 * 06 MVP-ADR-06 "Transfers and one engine"): it reads and writes this table only. Transfers have
 * their own table and their own store in the transfer module.
 */
@Repository
public class IdempotencyRecordRepository implements IdempotencyStore {

  private static final String KEY =
      "customer_id = :customer AND operation = :operation AND idempotency_key = :key";

  // A rejection body is built in SQL, so no JSON is assembled from strings here.
  private static final String REJECTION_BODY =
      """
      CASE WHEN CAST(:code AS text) IS NULL THEN NULL
           ELSE jsonb_build_object('errorCode', CAST(:code AS text),
                                   'message', CAST(:message AS text)) END
      """;

  private final JdbcClient jdbc;

  public IdempotencyRecordRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts the record unless one exists for the key. If another open transaction has inserted the
   * same key, this waits for it to end (the primary key serialises concurrent duplicates).
   *
   * @param status null for a claim whose outcome follows in the same transaction; otherwise the
   *     stored rejection's status, with {@code errorCode} and {@code message}
   * @return true if this call inserted the row
   */
  @Override
  public boolean insertIfAbsent(
      UUID customerId,
      String operation,
      UUID key,
      String requestHash,
      Integer status,
      String errorCode,
      String message,
      Instant createdAt,
      Instant expiresAt) {
    return jdbc.sql(
                """
                INSERT INTO wallet.idempotency_records
                  (customer_id, operation, idempotency_key, request_hash, response_status,
                   response_body, created_at, expires_at)
                VALUES (:customer, :operation, :key, :hash, :status,
                """
                    + REJECTION_BODY
                    + """
                    , :created, :expires)
                    ON CONFLICT (customer_id, operation, idempotency_key) DO NOTHING
                    """)
            .param("customer", customerId)
            .param("operation", operation)
            .param("key", key)
            .param("hash", requestHash)
            .param("status", status, Types.SMALLINT)
            .param("code", errorCode, Types.VARCHAR)
            .param("message", message, Types.VARCHAR)
            .param("created", Timestamps.bind(createdAt))
            .param("expires", Timestamps.bind(expiresAt))
            .update()
        == 1;
  }

  /**
   * Locks and reads the committed record; waits while another transaction holds it.
   *
   * @return empty if there is none, for example because the purge deleted it meanwhile
   */
  @Override
  public Optional<IdempotencyRecordRow> findForUpdate(UUID customerId, String operation, UUID key) {
    return select(customerId, operation, key, " FOR UPDATE");
  }

  /**
   * Reads the committed record without a lock, and without waiting for a transaction that holds it
   * (the engine's early replay).
   *
   * @return empty if there is none, or if it is not committed yet
   */
  @Override
  public Optional<IdempotencyRecordRow> find(UUID customerId, String operation, UUID key) {
    return select(customerId, operation, key, "");
  }

  private Optional<IdempotencyRecordRow> select(
      UUID customerId, String operation, UUID key, String lock) {
    return jdbc.sql(
            """
            SELECT request_hash, response_status, response_body::text AS body,
                   response_body ->> 'errorCode' AS error_code,
                   response_body ->> 'message' AS error_message, expires_at
            FROM wallet.idempotency_records WHERE
            """
                + KEY
                + lock)
        .param("customer", customerId)
        .param("operation", operation)
        .param("key", key)
        .query(
            (rs, row) ->
                new IdempotencyRecordRow(
                    rs.getString("request_hash"),
                    rs.getObject("response_status", Integer.class),
                    rs.getString("body"),
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    Timestamps.read(rs, "expires_at")))
        .optional();
  }

  /**
   * Replaces a locked record that no longer counts (expired, or without an outcome) by a new one;
   * parameters as {@link #insertIfAbsent}.
   */
  @Override
  public void replace(
      UUID customerId,
      String operation,
      UUID key,
      String requestHash,
      Integer status,
      String errorCode,
      String message,
      Instant createdAt,
      Instant expiresAt) {
    int updated =
        jdbc.sql(
                """
                UPDATE wallet.idempotency_records
                SET request_hash = :hash, response_status = :status, response_body =
                """
                    + REJECTION_BODY
                    + """
                    , created_at = :created, expires_at = :expires
                    WHERE
                    """
                    + KEY)
            .param("customer", customerId)
            .param("operation", operation)
            .param("key", key)
            .param("hash", requestHash)
            .param("status", status, Types.SMALLINT)
            .param("code", errorCode, Types.VARCHAR)
            .param("message", message, Types.VARCHAR)
            .param("created", Timestamps.bind(createdAt))
            .param("expires", Timestamps.bind(expiresAt))
            .update();
    requireOne(updated);
  }

  /** Stores the success outcome on the record this transaction claimed. */
  @Override
  public void complete(UUID customerId, String operation, UUID key, int status, String jsonBody) {
    int updated =
        jdbc.sql(
                "UPDATE wallet.idempotency_records"
                    + " SET response_status = :status, response_body = CAST(:body AS jsonb)"
                    + " WHERE "
                    + KEY
                    + " AND response_status IS NULL")
            .param("customer", customerId)
            .param("operation", operation)
            .param("key", key)
            .param("status", status, Types.SMALLINT)
            .param("body", jsonBody)
            .update();
    requireOne(updated);
  }

  /**
   * Deletes at most {@code limit} records that expired at or before {@code now}, in one statement.
   * Rows locked by a money transaction are skipped ({@code SKIP LOCKED}), so the purge never waits
   * on, or blocks, a live movement; a skipped row is deleted by a later run if it is still expired.
   */
  @Override
  public int deleteExpired(Instant now, int limit) {
    return jdbc.sql(
            """
            DELETE FROM wallet.idempotency_records
            WHERE (customer_id, operation, idempotency_key) IN (
              SELECT customer_id, operation, idempotency_key FROM wallet.idempotency_records
              WHERE expires_at <= :now
              ORDER BY expires_at
              LIMIT :limit
              FOR UPDATE SKIP LOCKED)
            """)
        .param("now", Timestamps.bind(now))
        .param("limit", limit)
        .update();
  }

  private static void requireOne(int updated) {
    if (updated != 1) {
      throw new IllegalStateException("idempotency record not found under its lock");
    }
  }
}
