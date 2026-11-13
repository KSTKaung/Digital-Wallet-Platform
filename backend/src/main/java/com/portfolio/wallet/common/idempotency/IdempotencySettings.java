package com.portfolio.wallet.common.idempotency;

import java.time.Duration;
import java.util.Objects;

/**
 * Startup checks shared by every module's idempotency configuration. Each module binds its own
 * prefix ({@code app.wallet.idempotency}, {@code app.transfer.idempotency}) and passes it here, so
 * a failure names the property that is wrong.
 */
public final class IdempotencySettings {

  /**
   * At least 1 second (owner, 2026-11-05, AA3): {@code timestamptz} keeps microseconds, so a
   * smaller TTL could make {@code expires_at} equal {@code created_at} and fail the tables' expiry
   * checks (V10 {@code ck_wallet_idempotency_records_expiry}, V11 {@code
   * ck_transfer_idempotency_records_expiry}) on every money POST.
   */
  public static final Duration MIN_TTL = Duration.ofSeconds(1);

  /** At most 30 days, the retention ceiling for idempotency keys (doc 03 §8). */
  public static final Duration MAX_TTL = Duration.ofDays(30);

  private IdempotencySettings() {}

  /**
   * @param prefix the module's property prefix ending in a dot, such as {@code
   *     "app.wallet.idempotency."}
   * @return {@code ttl}
   * @throws IllegalArgumentException if it is missing or outside 1 second to 30 days
   */
  public static Duration requireValidTtl(String prefix, Duration ttl) {
    Objects.requireNonNull(prefix, "prefix");
    if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException(
          prefix + "ttl must be at least 1 second and at most 30 days");
    }
    return ttl;
  }

  /**
   * Checks the purge job's settings.
   *
   * @throws IllegalArgumentException if the interval is missing or not positive, or the batch size
   *     is below 1
   */
  public static void requireValidPurge(String prefix, Duration interval, int batchSize) {
    Objects.requireNonNull(prefix, "prefix");
    if (interval == null || interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException(prefix + "purge.interval must be positive");
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException(prefix + "purge.batch-size must be at least 1");
    }
  }
}
