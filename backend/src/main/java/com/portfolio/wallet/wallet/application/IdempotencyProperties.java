package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.common.idempotency.IdempotencySettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Idempotency records of deposits and withdrawals. Checked at startup, with the checks shared by
 * every module ({@link IdempotencySettings}).
 *
 * @param ttl how long a key is retained: 24 hours (documentations/02 §1.4; doc 07 D04). At most 30
 *     days, the retention ceiling for idempotency keys (doc 03 §8), and at least 1 second (owner,
 *     2026-11-05, AA3): {@code timestamptz} keeps microseconds, so a smaller TTL could make {@code
 *     expires_at} equal {@code created_at} and fail V10's {@code
 *     ck_wallet_idempotency_records_expiry} on every money POST
 * @param purge the scheduled job that deletes expired records
 */
@ConfigurationProperties(prefix = "app.wallet.idempotency")
public record IdempotencyProperties(Duration ttl, Purge purge) {

  private static final String PREFIX = "app.wallet.idempotency.";
  static final Duration MIN_TTL = IdempotencySettings.MIN_TTL;
  static final Duration MAX_TTL = IdempotencySettings.MAX_TTL;

  public IdempotencyProperties {
    IdempotencySettings.requireValidTtl(PREFIX, ttl);
    if (purge == null) {
      throw new IllegalArgumentException(PREFIX + "purge must be configured");
    }
  }

  /**
   * @param enabled turns the scheduled job on; tests turn it off and call the purge directly
   * @param interval how often the job runs; not a documented value
   * @param batchSize the most rows one DELETE statement removes, so each statement is short; not a
   *     documented value
   */
  public record Purge(boolean enabled, Duration interval, int batchSize) {

    public Purge {
      IdempotencySettings.requireValidPurge(PREFIX, interval, batchSize);
    }
  }
}
