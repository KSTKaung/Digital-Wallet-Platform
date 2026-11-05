package com.portfolio.wallet.wallet.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Wallet-number lookup limits and purge (documentations/06 MVP-ADR-20; doc 07 D26). Checked at
 * startup.
 *
 * @param perUser lookups per window per signed-in user: 10 (MVP-ADR-20)
 * @param perIp lookups per window per client IP (IPv6: per /64): 30 (MVP-ADR-20)
 * @param window the sliding window: 1 minute (MVP-ADR-20); whole seconds
 * @param purge deletes attempts that no window can count any more
 */
@ConfigurationProperties(prefix = "app.wallet.lookup")
public record LookupProperties(int perUser, int perIp, Duration window, Purge purge) {

  private static final String PREFIX = "app.wallet.lookup.";

  public LookupProperties {
    if (perUser < 1 || perIp < 1) {
      throw new IllegalArgumentException(PREFIX + "per-user and per-ip must be at least 1");
    }
    if (window == null
        || window.compareTo(Duration.ofSeconds(1)) < 0
        || window.toNanosPart() != 0) {
      throw new IllegalArgumentException(PREFIX + "window must be whole seconds, at least 1s");
    }
    if (purge == null) {
      throw new IllegalArgumentException(PREFIX + "purge must be configured");
    }
  }

  /**
   * @param enabled turns the scheduled purge on; tests turn it off and call it directly
   * @param interval how often the purge runs; not a documented value
   * @param batchSize the most rows one DELETE removes; not a documented value
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
