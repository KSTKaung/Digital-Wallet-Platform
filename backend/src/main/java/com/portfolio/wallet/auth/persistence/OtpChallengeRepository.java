package com.portfolio.wallet.auth.persistence;

import com.portfolio.wallet.auth.domain.OtpChallengeState;
import com.portfolio.wallet.auth.domain.OtpIssueHistory;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.otp_challenges}. Rows stay after they end, because the limits count them. */
@Repository
public class OtpChallengeRepository {

  private final JdbcClient jdbc;

  public OtpChallengeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Serialises issuing for one identifier until the transaction ends, so concurrent resends cannot
   * pass a limit together or collide on the one-live-challenge index.
   */
  public void lockIdentifier(String identifierHash) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:hash, 0))")
        .param("hash", identifierHash)
        .query((rs, row) -> Boolean.TRUE)
        .list();
  }

  public OtpIssueHistory history(
      String identifierHash, OtpPurpose purpose, Instant hourStart, Instant usedUpStart) {
    return jdbc.sql(
            """
            SELECT
              min(sent_at) AS first_sent_at,
              max(sent_at) AS last_sent_at,
              count(*) FILTER (WHERE sent_at > :hourStart) AS sends_in_hour,
              (SELECT count(*) FROM auth.otp_challenges u
               WHERE u.identifier_hash = :hash AND u.used_up_at > :usedUpStart) AS used_up,
              (SELECT coalesce(sum(g.attempts), 0) FROM auth.otp_challenges g
               WHERE g.identifier_hash = :hash
                 AND (g.sent_at > :usedUpStart OR g.used_up_at > :usedUpStart)) AS wrong_guesses
            FROM auth.otp_challenges
            WHERE identifier_hash = :hash AND purpose = :purpose
            """)
        .param("hash", identifierHash)
        .param("purpose", purpose.name())
        .param("hourStart", Timestamps.bind(hourStart))
        .param("usedUpStart", Timestamps.bind(usedUpStart))
        .query(
            (rs, row) ->
                new OtpIssueHistory(
                    Timestamps.read(rs, "first_sent_at"),
                    Timestamps.read(rs, "last_sent_at"),
                    rs.getLong("sends_in_hour"),
                    rs.getLong("used_up"),
                    rs.getLong("wrong_guesses")))
        .single();
  }

  public void cancelLive(String identifierHash, OtpPurpose purpose, Instant now) {
    jdbc.sql(
            """
            UPDATE auth.otp_challenges SET cancelled_at = :now
            WHERE identifier_hash = :hash AND purpose = :purpose
              AND consumed_at IS NULL AND cancelled_at IS NULL AND used_up_at IS NULL
            """)
        .param("now", Timestamps.bind(now))
        .param("hash", identifierHash)
        .param("purpose", purpose.name())
        .update();
  }

  public void insert(
      String identifierHash,
      OtpPurpose purpose,
      String otpHash,
      Instant sentAt,
      Instant expiresAt) {
    jdbc.sql(
            """
            INSERT INTO auth.otp_challenges
              (identifier_hash, purpose, otp_hash, sent_at, expires_at)
            VALUES (:hash, :purpose, :otp, :sentAt, :expiresAt)
            """)
        .param("hash", identifierHash)
        .param("purpose", purpose.name())
        .param("otp", otpHash)
        .param("sentAt", Timestamps.bind(sentAt))
        .param("expiresAt", Timestamps.bind(expiresAt))
        .update();
  }

  /**
   * Locks the live challenge, or else the latest one, so the audit can say why a code was refused.
   * The row lock is what makes concurrent guesses count one at a time; the state read here is the
   * state after any guess this one waited for.
   */
  public Optional<OtpChallengeRow> latestForUpdate(String identifierHash, OtpPurpose purpose) {
    return jdbc.sql(
            """
            SELECT id, otp_hash, attempts, expires_at, consumed_at, cancelled_at, used_up_at
            FROM auth.otp_challenges
            WHERE identifier_hash = :hash AND purpose = :purpose
            ORDER BY (consumed_at IS NULL AND cancelled_at IS NULL AND used_up_at IS NULL) DESC,
                     sent_at DESC
            LIMIT 1
            FOR UPDATE
            """)
        .param("hash", identifierHash)
        .param("purpose", purpose.name())
        .query(
            (rs, row) ->
                new OtpChallengeRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("otp_hash"),
                    new OtpChallengeState(
                        rs.getInt("attempts"),
                        Timestamps.read(rs, "expires_at"),
                        Timestamps.read(rs, "consumed_at"),
                        Timestamps.read(rs, "cancelled_at"),
                        Timestamps.read(rs, "used_up_at"))))
        .optional();
  }

  /**
   * @param usedUpAt when this guess spent the challenge, otherwise null
   */
  public void recordWrongAttempt(UUID id, int attempts, Instant usedUpAt) {
    jdbc.sql(
            """
            UPDATE auth.otp_challenges
            SET attempts = :attempts, used_up_at = CAST(:usedUpAt AS timestamptz)
            WHERE id = :id
            """)
        .param("attempts", attempts)
        .param("usedUpAt", Timestamps.bind(usedUpAt))
        .param("id", id)
        .update();
  }

  /** Keeps a challenge until it is expired and outside every window a limit counts it in. */
  public int deleteExpired(Instant retainedSince, Instant now) {
    return jdbc.sql(
            """
            DELETE FROM auth.otp_challenges
            WHERE sent_at <= :cutoff AND expires_at <= :now
              AND (used_up_at IS NULL OR used_up_at <= :cutoff)
            """)
        .param("cutoff", Timestamps.bind(retainedSince))
        .param("now", Timestamps.bind(now))
        .update();
  }

  public void consume(UUID id, Instant now) {
    jdbc.sql("UPDATE auth.otp_challenges SET consumed_at = :now WHERE id = :id")
        .param("now", Timestamps.bind(now))
        .param("id", id)
        .update();
  }
}
