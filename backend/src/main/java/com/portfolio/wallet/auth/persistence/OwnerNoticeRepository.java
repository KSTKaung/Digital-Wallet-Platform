package com.portfolio.wallet.auth.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.owner_notices}: when each account's owner last got a registration notice. */
@Repository
public class OwnerNoticeRepository {

  private final JdbcClient jdbc;

  public OwnerNoticeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One statement, so of concurrent claims the others wait for the first to commit and then find it
   * too recent.
   *
   * @param sentBefore the latest earlier notice that still allows a new one
   * @return whether this call may send the notice
   */
  public boolean claim(UUID userId, Instant now, Instant sentBefore) {
    return jdbc.sql(
            """
            INSERT INTO auth.owner_notices AS n (user_id, last_sent_at)
            VALUES (:user, :now)
            ON CONFLICT (user_id) DO UPDATE SET last_sent_at = EXCLUDED.last_sent_at
            WHERE n.last_sent_at <= :sentBefore
            RETURNING user_id
            """)
        .param("user", userId)
        .param("now", Timestamps.bind(now))
        .param("sentBefore", Timestamps.bind(sentBefore))
        .query(UUID.class)
        .optional()
        .isPresent();
  }
}
