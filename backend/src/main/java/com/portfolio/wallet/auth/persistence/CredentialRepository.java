package com.portfolio.wallet.auth.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.credentials} and {@code auth.password_history}. */
@Repository
public class CredentialRepository {

  private final JdbcClient jdbc;

  public CredentialRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID userId, String passwordHash, Instant now) {
    jdbc.sql(
            """
            INSERT INTO auth.credentials (user_id, password_hash, password_changed_at)
            VALUES (:user, :hash, :now)
            """)
        .param("user", userId)
        .param("hash", passwordHash)
        .param("now", Timestamps.bind(now))
        .update();
    addToHistory(userId, passwordHash);
  }

  /**
   * Replaces the password and clears any lock, if the stored hash is still {@code expectedHash}.
   *
   * @return false, with nothing changed, when another change got there first
   */
  public boolean replacePassword(UUID userId, String expectedHash, String newHash, Instant now) {
    return jdbc.sql(
                """
                UPDATE auth.credentials SET
                  password_hash = :newHash,
                  failed_login_count = 0,
                  locked_until = NULL,
                  password_changed_at = :now
                WHERE user_id = :user AND password_hash = :expected
                """)
            .param("newHash", newHash)
            .param("now", Timestamps.bind(now))
            .param("user", userId)
            .param("expected", expectedHash)
            .update()
        == 1;
  }

  /**
   * A re-registration of a pending account: nothing of the old password may remain, including its
   * history row and whatever lock its failures started. The caller holds the user's row lock.
   */
  public void overwritePending(UUID userId, String newHash, Instant now) {
    jdbc.sql(
            """
            UPDATE auth.credentials SET
              password_hash = :newHash,
              failed_login_count = 0,
              locked_until = NULL,
              password_changed_at = :now
            WHERE user_id = :user
            """)
        .param("newHash", newHash)
        .param("now", Timestamps.bind(now))
        .param("user", userId)
        .update();
    jdbc.sql("DELETE FROM auth.password_history WHERE user_id = :user")
        .param("user", userId)
        .update();
    addToHistory(userId, newHash);
  }

  /**
   * @return newest first
   */
  public List<String> recentHashes(UUID userId, int depth) {
    return jdbc.sql(
            """
            SELECT password_hash FROM auth.password_history
            WHERE user_id = :user ORDER BY created_at DESC, id DESC LIMIT :depth
            """)
        .param("user", userId)
        .param("depth", depth)
        .query(String.class)
        .list();
  }

  public void addToHistory(UUID userId, String passwordHash) {
    jdbc.sql("INSERT INTO auth.password_history (user_id, password_hash) VALUES (:user, :hash)")
        .param("user", userId)
        .param("hash", passwordHash)
        .update();
  }

  public void pruneHistory(UUID userId, int depth) {
    jdbc.sql(
            """
            DELETE FROM auth.password_history
            WHERE user_id = :user AND id NOT IN (
              SELECT id FROM auth.password_history
              WHERE user_id = :user ORDER BY created_at DESC, id DESC LIMIT :depth)
            """)
        .param("user", userId)
        .param("depth", depth)
        .update();
  }

  /**
   * Counts one failure in one statement. A row whose lock is still running is left alone (empty
   * result); a row whose lock has expired starts again from one.
   */
  public Optional<LockChange> recordFailure(
      UUID userId, int maxFailedAttempts, Instant lockUntil, Instant now) {
    return jdbc.sql(
            """
            UPDATE auth.credentials SET
              failed_login_count =
                CASE WHEN locked_until IS NOT NULL THEN 1 ELSE failed_login_count + 1 END,
              locked_until =
                CASE WHEN (CASE WHEN locked_until IS NOT NULL THEN 1
                                ELSE failed_login_count + 1 END) >= :max
                     THEN CAST(:lockUntil AS timestamptz) END
            WHERE user_id = :user AND (locked_until IS NULL OR locked_until <= :now)
            RETURNING locked_until
            """)
        .param("max", maxFailedAttempts)
        .param("lockUntil", Timestamps.bind(lockUntil))
        .param("user", userId)
        .param("now", Timestamps.bind(now))
        .query((rs, row) -> new LockChange(Timestamps.read(rs, "locked_until") != null))
        .optional();
  }

  /**
   * @param checkedHash the hash the login just verified against
   * @return false when a lock started or the password changed meanwhile; nothing changed then
   */
  public boolean resetFailures(UUID userId, String checkedHash, Instant now) {
    return jdbc.sql(
                """
                UPDATE auth.credentials SET failed_login_count = 0, locked_until = NULL
                WHERE user_id = :user AND password_hash = :checked
                  AND (locked_until IS NULL OR locked_until <= :now)
                """)
            .param("user", userId)
            .param("checked", checkedHash)
            .param("now", Timestamps.bind(now))
            .update()
        == 1;
  }
}
