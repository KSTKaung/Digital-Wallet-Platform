package com.portfolio.wallet.auth.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.login_failure_counters}: unknown emails, by keyed hash only. */
@Repository
public class LoginFailureRepository {

  private final JdbcClient jdbc;

  public LoginFailureRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Instant> findLockedUntil(String identifierHash) {
    return jdbc.sql(
            """
            SELECT locked_until FROM auth.login_failure_counters
            WHERE identifier_hash = :hash AND locked_until IS NOT NULL
            """)
        .param("hash", identifierHash)
        .query((rs, row) -> Timestamps.read(rs, "locked_until"))
        .optional();
  }

  public void delete(String identifierHash) {
    jdbc.sql("DELETE FROM auth.login_failure_counters WHERE identifier_hash = :hash")
        .param("hash", identifierHash)
        .update();
  }

  /**
   * Past the retention and not locked, so that no running lock is lifted early. {@code updated_at}
   * is set by the database, so its age is measured by the database's clock; {@code locked_until} is
   * written from the application's, and is compared with that.
   */
  public int deleteExpired(Duration retention, Instant now) {
    return jdbc.sql(
            """
            DELETE FROM auth.login_failure_counters
            WHERE updated_at <= now() - make_interval(secs => CAST(:seconds AS double precision))
              AND (locked_until IS NULL OR locked_until <= :now)
            """)
        .param("seconds", retention.toSeconds())
        .param("now", Timestamps.bind(now))
        .update();
  }

  /** Same rules as {@link CredentialRepository#recordFailure}, as one upsert. */
  public Optional<LockChange> recordFailure(
      String identifierHash, int maxFailedAttempts, Instant lockUntil, Instant now) {
    return jdbc.sql(
            """
            INSERT INTO auth.login_failure_counters AS c
              (identifier_hash, failed_count, locked_until)
            VALUES (:hash, 1, CASE WHEN 1 >= :max THEN CAST(:lockUntil AS timestamptz) END)
            ON CONFLICT (identifier_hash) DO UPDATE SET
              failed_count = CASE WHEN c.locked_until IS NOT NULL THEN 1 ELSE c.failed_count + 1 END,
              locked_until =
                CASE WHEN (CASE WHEN c.locked_until IS NOT NULL THEN 1
                                ELSE c.failed_count + 1 END) >= :max
                     THEN CAST(:lockUntil AS timestamptz) END
            WHERE c.locked_until IS NULL OR c.locked_until <= :now
            RETURNING locked_until
            """)
        .param("hash", identifierHash)
        .param("max", maxFailedAttempts)
        .param("lockUntil", Timestamps.bind(lockUntil))
        .param("now", Timestamps.bind(now))
        .query((rs, row) -> new LockChange(Timestamps.read(rs, "locked_until") != null))
        .optional();
  }
}
