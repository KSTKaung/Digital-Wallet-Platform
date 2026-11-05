package com.portfolio.wallet.wallet.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code wallet.daily_limit_usage} (V3; documentations/06 MVP-ADR-18, -22): one row per wallet,
 * operation and Asia/Bangkok business day. The row is locked after the wallet (doc 07 §8 lock
 * order), so the check and the update of the day's usage are one step for concurrent requests.
 */
@Repository
public class DailyLimitUsageRepository {

  private final JdbcClient jdbc;

  public DailyLimitUsageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Creates the day's row if it is missing and locks it. The caller holds the wallet lock, so no
   * other money flow on this wallet can create the same row meanwhile.
   */
  public DailyLimitUsageRow lockOrCreate(UUID walletId, String operation, LocalDate limitDate) {
    jdbc.sql(
            """
            INSERT INTO wallet.daily_limit_usage (wallet_id, operation, limit_date)
            VALUES (:wallet, :operation, :date)
            ON CONFLICT (wallet_id, operation, limit_date) DO NOTHING
            """)
        .param("wallet", walletId)
        .param("operation", operation)
        .param("date", limitDate)
        .update();
    return jdbc.sql(
            """
            SELECT used, reserved, version FROM wallet.daily_limit_usage
            WHERE wallet_id = :wallet AND operation = :operation AND limit_date = :date
            FOR UPDATE
            """)
        .param("wallet", walletId)
        .param("operation", operation)
        .param("date", limitDate)
        .query(
            (rs, row) ->
                new DailyLimitUsageRow(
                    rs.getBigDecimal("used"), rs.getBigDecimal("reserved"), rs.getLong("version")))
        .single();
  }

  /**
   * Sets the day's completed usage with the optimistic {@code version} check (MVP-ADR-08).
   *
   * @return 1, or 0 if the version is no longer {@code expectedVersion}
   */
  public int updateUsed(
      UUID walletId, String operation, LocalDate limitDate, BigDecimal used, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE wallet.daily_limit_usage SET used = :used, version = version + 1
            WHERE wallet_id = :wallet AND operation = :operation AND limit_date = :date
              AND version = :version
            """)
        .param("used", used)
        .param("wallet", walletId)
        .param("operation", operation)
        .param("date", limitDate)
        .param("version", expectedVersion)
        .update();
  }
}
