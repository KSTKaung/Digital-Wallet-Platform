package com.portfolio.wallet.wallet.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code wallet.lookup_attempts} (V3; documentations/06 MVP-ADR-20; doc 07 D26): one row per wallet
 * lookup, read as sliding windows per user and per client IP. The database sets {@code
 * attempted_at}, so the application cannot move an attempt out of a window. The runtime role may
 * read, insert and delete (the purge), never update.
 *
 * <p><b>Time (owner, 2026-11-04, Y1).</b> Every time here is {@code statement_timestamp()}, the
 * start of the current statement, never {@code now()}, the start of the transaction. The advisory
 * locks are taken first, in statements of their own ({@link #lockKeys}), and a lookup may wait
 * there; the window counts, the {@code Retry-After} and the attempt {@code INSERT} (whose trigger
 * stamps {@code statement_timestamp()} since V10) are later statements, so they all see the time
 * after the wait. With {@code now()} they would see the time before it, and a long wait would count
 * attempts that have already left the window. Changing the function alone is not enough: the time
 * must be read by a statement that runs after the lock is held, which is why the locks are separate
 * statements. The purge uses the same clock.
 */
@Repository
public class LookupAttemptRepository {

  /**
   * How many attempts a key has in the window, and in how many whole seconds the oldest of them
   * leaves it.
   *
   * @param secondsUntilOldestLeaves 0 when there are none
   */
  public record WindowCount(int count, long secondsUntilOldestLeaves) {}

  private final JdbcClient jdbc;

  public LookupAttemptRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Serialises the attempts of one user, then of one client IP, until the transaction ends, so a
   * count and the insert that follows it are one step (no lost increments). Every caller takes the
   * user lock before the IP lock, so two lookups never wait on each other in a cycle.
   */
  public void lockKeys(UUID userId, String clientIp) {
    lock("wallet.lookup.user:" + userId);
    lock("wallet.lookup.ip:" + clientIp);
  }

  private void lock(String key) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", key)
        .query((rs, row) -> 1)
        .single();
  }

  public WindowCount countForUser(UUID userId, long windowSeconds) {
    return count("user_id = :key", userId, windowSeconds);
  }

  /**
   * @param clientIp an IP literal, as stored by {@link #insert}
   */
  public WindowCount countForIp(String clientIp, long windowSeconds) {
    return count("client_ip = CAST(:key AS inet)", clientIp, windowSeconds);
  }

  // The condition is one of this class's constants, never client input.
  private WindowCount count(String condition, Object key, long windowSeconds) {
    return jdbc.sql(
            "SELECT count(*) AS n,"
                + " coalesce(ceil(extract(epoch FROM"
                + " (min(attempted_at) + make_interval(secs => :window) - statement_timestamp()))), 0) AS wait"
                + " FROM wallet.lookup_attempts WHERE "
                + condition
                + " AND attempted_at > statement_timestamp() - make_interval(secs => :window)")
        .param("key", key)
        .param("window", windowSeconds)
        .query((rs, row) -> new WindowCount(rs.getInt("n"), rs.getLong("wait")))
        .single();
  }

  /** Records an attempt; the database sets the time. */
  public void insert(UUID userId, String clientIp) {
    jdbc.sql(
            "INSERT INTO wallet.lookup_attempts (user_id, client_ip)"
                + " VALUES (:user, CAST(:ip AS inet))")
        .param("user", userId)
        .param("ip", clientIp)
        .update();
  }

  /**
   * Deletes at most {@code limit} attempts older than {@code retentionSeconds}, in one statement.
   * No row lock clause: it needs UPDATE, which V3 revokes on this table, and nothing else ever
   * locks an attempt, so the delete waits on nobody. A concurrent purge that reaches a row first
   * simply leaves this statement nothing to delete there.
   */
  public int deleteOlderThan(long retentionSeconds, int limit) {
    return jdbc.sql(
            """
            DELETE FROM wallet.lookup_attempts WHERE id IN (
              SELECT id FROM wallet.lookup_attempts
              WHERE attempted_at < statement_timestamp() - make_interval(secs => :retention)
              ORDER BY attempted_at
              LIMIT :limit)
            """)
        .param("retention", retentionSeconds)
        .param("limit", limit)
        .update();
  }
}
