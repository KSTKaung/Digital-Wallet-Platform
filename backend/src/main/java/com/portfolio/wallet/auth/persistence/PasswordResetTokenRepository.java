package com.portfolio.wallet.auth.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.password_reset_tokens}: keyed hashes only, single-use. */
@Repository
public class PasswordResetTokenRepository {

  private final JdbcClient jdbc;

  public PasswordResetTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void invalidateUnused(UUID userId, Instant now) {
    jdbc.sql(
            """
            UPDATE auth.password_reset_tokens SET used_at = :now
            WHERE user_id = :user AND used_at IS NULL
            """)
        .param("now", Timestamps.bind(now))
        .param("user", userId)
        .update();
  }

  public void insert(UUID userId, String tokenHash, Instant expiresAt) {
    jdbc.sql(
            """
            INSERT INTO auth.password_reset_tokens (user_id, token_hash, expires_at)
            VALUES (:user, :hash, :expiresAt)
            """)
        .param("user", userId)
        .param("hash", tokenHash)
        .param("expiresAt", Timestamps.bind(expiresAt))
        .update();
  }

  /** Reads without consuming, so that a password the policy refuses leaves the token usable. */
  public Optional<UUID> findUsableOwner(String tokenHash, Instant now) {
    return jdbc.sql(
            """
            SELECT user_id FROM auth.password_reset_tokens
            WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now
            """)
        .param("hash", tokenHash)
        .param("now", Timestamps.bind(now))
        .query(UUID.class)
        .optional();
  }

  /**
   * One conditional statement: of concurrent uses, the second waits on the row, then finds {@code
   * used_at} set and changes nothing. The account's status is part of the condition, so a token
   * issued before a freeze cannot be spent after it.
   *
   * @return whether this call won the token
   */
  public boolean consume(String tokenHash, String requiredStatus, Instant now) {
    return jdbc.sql(
                """
                UPDATE auth.password_reset_tokens t SET used_at = :now
                WHERE t.token_hash = :hash AND t.used_at IS NULL AND t.expires_at > :now
                  AND EXISTS (SELECT 1 FROM auth.users u
                              WHERE u.id = t.user_id AND u.status = :status)
                """)
            .param("now", Timestamps.bind(now))
            .param("hash", tokenHash)
            .param("status", requiredStatus)
            .update()
        == 1;
  }

  public int deleteExpired(Instant now) {
    return jdbc.sql("DELETE FROM auth.password_reset_tokens WHERE expires_at <= :now")
        .param("now", Timestamps.bind(now))
        .update();
  }
}
