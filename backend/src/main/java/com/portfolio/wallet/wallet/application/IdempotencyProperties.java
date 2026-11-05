package com.portfolio.wallet.wallet.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Idempotency records of deposits and withdrawals. Checked at startup.
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
  static final Duration MIN_TTL = Duration.ofSeconds(1);
  static final Duration MAX_TTL = Duration.ofDays(30);

  public IdempotencyProperties {
    if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException(
          PREFIX + "ttl must be at least 1 second and at most 30 days");
    }
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
      if (interval == null || interval.isNegative() || interval.isZero()) {
        throw new IllegalArgumentException(PREFIX + "purge.interval must be positive");
      }
      if (batchSize < 1) {
        throw new IllegalArgumentException(PREFIX + "purge.batch-size must be at least 1");
      }
    }
  }
}
