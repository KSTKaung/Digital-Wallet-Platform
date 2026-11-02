package com.portfolio.wallet.auth.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.refresh_tokens}: hashes only; a session is the token family. */
@Repository
public class RefreshTokenRepository {

  private final JdbcClient jdbc;

  public RefreshTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID sessionId, String tokenHash, Instant expiresAt) {
    jdbc.sql(
            """
            INSERT INTO auth.refresh_tokens (session_id, token_hash, expires_at)
            VALUES (:session, :hash, :expiresAt)
            """)
        .param("session", sessionId)
        .param("hash", tokenHash)
        .param("expiresAt", Timestamps.bind(expiresAt))
        .update();
  }

  /** Without a lock: this says only which session to lock first. */
  public Optional<UUID> findSessionId(String tokenHash) {
    return jdbc.sql("SELECT session_id FROM auth.refresh_tokens WHERE token_hash = :hash")
        .param("hash", tokenHash)
        .query(UUID.class)
        .optional();
  }

  public Optional<RefreshTokenRow> findForUpdate(String tokenHash) {
    return jdbc.sql(
            """
            SELECT id, session_id, expires_at, used_at, revoked_at
            FROM auth.refresh_tokens WHERE token_hash = :hash FOR UPDATE
            """)
        .param("hash", tokenHash)
        .query(
            (rs, row) ->
                new RefreshTokenRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("session_id", UUID.class),
                    Timestamps.read(rs, "expires_at"),
                    Timestamps.read(rs, "used_at"),
                    Timestamps.read(rs, "revoked_at")))
        .optional();
  }

  public void markUsed(UUID id, Instant now) {
    jdbc.sql("UPDATE auth.refresh_tokens SET used_at = :now WHERE id = :id")
        .param("now", Timestamps.bind(now))
        .param("id", id)
        .update();
  }

  /**
   * Leaves expired tokens alone, here and in {@link #revokeAll}: they are refused anyway, and these
   * statements thereby stay off the rows {@link #deleteExpired} may hold.
   */
  public void revokeAllForUser(UUID userId, Instant now) {
    jdbc.sql(
            """
            UPDATE auth.refresh_tokens SET revoked_at = :now
            WHERE revoked_at IS NULL AND expires_at > :now AND session_id IN (
              SELECT id FROM auth.sessions WHERE user_id = :user)
            """)
        .param("now", Timestamps.bind(now))
        .param("user", userId)
        .update();
  }

  /** Only once expired: until then a used token is what detects reuse. */
  public int deleteExpired(Instant now) {
    return jdbc.sql("DELETE FROM auth.refresh_tokens WHERE expires_at <= :now")
        .param("now", Timestamps.bind(now))
        .update();
  }

  public void revokeAll(UUID sessionId, Instant now) {
    jdbc.sql(
            """
            UPDATE auth.refresh_tokens SET revoked_at = :now
            WHERE session_id = :session AND revoked_at IS NULL AND expires_at > :now
            """)
        .param("now", Timestamps.bind(now))
        .param("session", sessionId)
        .update();
  }
}
