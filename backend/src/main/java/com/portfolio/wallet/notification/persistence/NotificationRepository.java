package com.portfolio.wallet.notification.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code notification.notifications}, the email outbox (V9; documentations/06 MVP-ADR-25). Plain
 * SQL, as in the other modules: the database-set columns and the per-column grants stay visible in
 * each statement.
 *
 * <p>Attempts count claims (owner, 2026-11-04, R1). A claim is one statement that adds one to
 * {@code attempts} and leases the row: it moves {@code next_attempt_at} past the lease, so no other
 * run claims it while it is being sent. The new attempt count is the claim's fencing token: every
 * result is conditional on {@code PENDING} and on that count, so the result of an older claim,
 * whose lease ran out and whose row was claimed again, changes nothing. No claim goes past the
 * configured maximum, and a last claim whose lease ran out without a result is marked {@code
 * FAILED}. V9's trigger refuses any change to a {@code SENT} or {@code FAILED} row as the last
 * guard.
 */
@Repository
public class NotificationRepository {

  private static final String CLAIM =
      """
      UPDATE notification.notifications
      SET attempts = attempts + 1, next_attempt_at = :leaseUntil
      WHERE event_id = (
        SELECT event_id FROM notification.notifications
        WHERE status = 'PENDING' AND next_attempt_at <= :now AND attempts < :maxAttempts %s
        ORDER BY next_attempt_at, event_id
        LIMIT 1
        FOR UPDATE SKIP LOCKED)
      RETURNING event_id, user_id, kind, attempts, occurred_at, CAST(payload AS text) AS payload
      """;

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public NotificationRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Inserts a {@code PENDING} row, due at {@code nextAttemptAt}; the database sets the time of the
   * change and the other stamps (V9).
   */
  public void insert(
      UUID eventId, UUID userId, String kind, Map<String, String> values, Instant nextAttemptAt) {
    jdbc.sql(
            """
            INSERT INTO notification.notifications
              (event_id, user_id, kind, payload, next_attempt_at)
            VALUES (:event, :user, :kind, CAST(:payload AS jsonb), :next)
            """)
        .param("event", eventId)
        .param("user", userId)
        .param("kind", kind)
        .param("payload", json.writeValueAsString(values))
        .param("next", bind(nextAttemptAt))
        .update();
  }

  /**
   * Claims the oldest due {@code PENDING} row that has had fewer than {@code maxAttempts} claims:
   * adds one to its attempts and leases it until {@code leaseUntil}, skipping rows another run
   * holds. The conditions are evaluated under the row lock, so a row that another run finished or
   * leased meanwhile is not returned. Commit promptly: the lock is held until then.
   *
   * @return the row with its new attempt count, the claim's fencing token
   */
  public Optional<OutboxRow> claimNextDue(Instant now, Instant leaseUntil, int maxAttempts) {
    return jdbc.sql(CLAIM.formatted(""))
        .param("now", bind(now))
        .param("leaseUntil", bind(leaseUntil))
        .param("maxAttempts", maxAttempts)
        .query((rs, row) -> map(rs))
        .optional();
  }

  /** As {@link #claimNextDue}, for one row. */
  public Optional<OutboxRow> claimDue(
      UUID eventId, Instant now, Instant leaseUntil, int maxAttempts) {
    return jdbc.sql(CLAIM.formatted("AND event_id = :event"))
        .param("now", bind(now))
        .param("leaseUntil", bind(leaseUntil))
        .param("maxAttempts", maxAttempts)
        .param("event", eventId)
        .query((rs, row) -> map(rs))
        .optional();
  }

  /**
   * Marks {@code FAILED} every row whose last allowed claim leased it and ran out without a result
   * (R1: a crash or a lost result on the last attempt), so it is never claimed a further time.
   *
   * @return the rows marked, for logging: event id and kind only
   */
  public List<OutboxRow> failExpiredLastClaims(Instant now, int maxAttempts) {
    return jdbc.sql(
            """
            UPDATE notification.notifications
            SET status = 'FAILED', last_failure = 'LeaseExpired'
            WHERE status = 'PENDING' AND attempts >= :maxAttempts AND next_attempt_at <= :now
            RETURNING event_id, user_id, kind, attempts, occurred_at, CAST(payload AS text) AS payload
            """)
        .param("now", bind(now))
        .param("maxAttempts", maxAttempts)
        .query((rs, row) -> map(rs))
        .list();
  }

  /**
   * @param claim the attempt count the claim returned, its fencing token
   * @return 1, or 0 if the row is no longer {@code PENDING} or a newer claim took it over
   */
  public int markSent(UUID eventId, int claim) {
    return jdbc.sql(
            """
            UPDATE notification.notifications SET status = 'SENT'
            WHERE event_id = :event AND status = 'PENDING' AND attempts = :claim
            """)
        .param("event", eventId)
        .param("claim", claim)
        .update();
  }

  /**
   * A failed attempt with another one to come at {@code nextAttemptAt}. The attempt count is not
   * changed: the claim counted it. As {@link #markSent}.
   */
  public int scheduleRetry(UUID eventId, int claim, Instant nextAttemptAt, String failure) {
    return jdbc.sql(
            """
            UPDATE notification.notifications
            SET next_attempt_at = :next, last_failure = :failure
            WHERE event_id = :event AND status = 'PENDING' AND attempts = :claim
            """)
        .param("claim", claim)
        .param("next", bind(nextAttemptAt))
        .param("failure", failure)
        .param("event", eventId)
        .update();
  }

  /** The last attempt failed: the row is {@code FAILED} for good. As {@link #markSent}. */
  public int markFailed(UUID eventId, int claim, String failure) {
    return jdbc.sql(
            """
            UPDATE notification.notifications
            SET status = 'FAILED', last_failure = :failure
            WHERE event_id = :event AND status = 'PENDING' AND attempts = :claim
            """)
        .param("claim", claim)
        .param("failure", failure)
        .param("event", eventId)
        .update();
  }

  private OutboxRow map(ResultSet rs) throws SQLException {
    return new OutboxRow(
        rs.getObject("event_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("kind"),
        rs.getInt("attempts"),
        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
        values(rs.getString("payload")));
  }

  /**
   * The stored template values, or {@code null} if the payload is not an object of strings: the
   * dispatcher then fails that attempt, so a bad row never fails the claim and stops the run.
   */
  private Map<String, String> values(String payload) {
    try {
      JsonNode node = json.readTree(payload);
      if (node == null || !node.isObject()) {
        return null;
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        if (!property.getValue().isString()) {
          return null;
        }
        values.put(property.getKey(), property.getValue().asString());
      }
      return values;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static OffsetDateTime bind(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
