package com.portfolio.wallet.auth.persistence;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.sessions}. */
@Repository
public class SessionRepository {

  private final JdbcClient jdbc;

  public SessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public UUID insert(UUID userId, Instant now) {
    return jdbc.sql(
            "INSERT INTO auth.sessions (user_id, last_seen_at) VALUES (:user, :now) RETURNING id")
        .param("user", userId)
        .param("now", Timestamps.bind(now))
        .query(UUID.class)
        .single();
  }

  /** One primary-key lookup per authenticated request. */
  public Optional<SessionRow> find(UUID sessionId) {
    return find(sessionId, "");
  }

  /**
   * Locks the session and reads it, for rotation. The session is the first lock of every path that
   * changes a session or its tokens, so none of them can deadlock on a token. NO KEY, so that
   * inserting the rotated token, whose foreign key takes a key-share lock, is not refused; the user
   * row stays unlocked.
   */
  public Optional<SessionRow> findForUpdate(UUID sessionId) {
    return find(sessionId, "FOR NO KEY UPDATE OF s");
  }

  // The suffix is one of this class's constants, never client input.
  private Optional<SessionRow> find(UUID sessionId, String lockClause) {
    return jdbc.sql(
            """
            SELECT s.id, s.user_id, s.last_seen_at, s.revoked_at, u.status, u.role
            FROM auth.sessions s
            JOIN auth.users u ON u.id = s.user_id
            WHERE s.id = :id
            """
                + lockClause)
        .param("id", sessionId)
        .query(
            (rs, row) ->
                new SessionRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    Timestamps.read(rs, "last_seen_at"),
                    Timestamps.read(rs, "revoked_at"),
                    UserStatus.valueOf(rs.getString("status")),
                    Role.valueOf(rs.getString("role"))))
        .optional();
  }

  /**
   * Writes only if the stored value is at or before {@code cutoff}, so concurrent calls coalesce.
   */
  public void touch(UUID sessionId, Instant now, Instant cutoff) {
    jdbc.sql(
            """
            UPDATE auth.sessions SET last_seen_at = :now
            WHERE id = :id AND revoked_at IS NULL AND last_seen_at <= :cutoff
            """)
        .param("now", Timestamps.bind(now))
        .param("id", sessionId)
        .param("cutoff", Timestamps.bind(cutoff))
        .update();
  }

  public void revokeAllForUser(UUID userId, String reason, Instant now) {
    jdbc.sql(
            """
            UPDATE auth.sessions SET revoked_at = :now, revoked_reason = :reason
            WHERE user_id = :user AND revoked_at IS NULL
            """)
        .param("now", Timestamps.bind(now))
        .param("reason", reason)
        .param("user", userId)
        .update();
  }

  /**
   * A session that has ended stays until its last refresh token expires, so that presenting one
   * still audits the real reason. Its remaining tokens go with it, by the foreign key.
   */
  public int deleteEnded(Instant idleCutoff, Instant now) {
    return jdbc.sql(
            """
            DELETE FROM auth.sessions s
            WHERE (s.revoked_at IS NOT NULL OR s.last_seen_at <= :idleCutoff)
              AND NOT EXISTS (SELECT 1 FROM auth.refresh_tokens r
                              WHERE r.session_id = s.id AND r.expires_at > :now)
            """)
        .param("idleCutoff", Timestamps.bind(idleCutoff))
        .param("now", Timestamps.bind(now))
        .update();
  }

  /**
   * @return whether this call revoked it, so one revocation is audited once
   */
  public boolean revoke(UUID sessionId, String reason, Instant now) {
    return jdbc.sql(
                """
                UPDATE auth.sessions SET revoked_at = :now, revoked_reason = :reason
                WHERE id = :id AND revoked_at IS NULL
                """)
            .param("now", Timestamps.bind(now))
            .param("reason", reason)
            .param("id", sessionId)
            .update()
        == 1;
  }
}
