package com.portfolio.wallet.transfer.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The expiry job of held transfers ({@code app.transfer.expiry}; documentations/06 MVP-ADR-24
 * "Expiry", "Jobs (Q11)"; doc 08 §4C.2 Q11). Checked at startup.
 *
 * @param enabled turns the scheduled job on; tests turn it off and call {@link
 *     TransferExpiryService#expireDue()} directly
 * @param interval how often the job runs: every 1 minute (Q11, MVP value)
 * @param maxPerRun the most due transfers one run expires or skips, so one run stays bounded; the
 *     rest wait for the next run. A transfer that fails does not count, so failures cannot use up
 *     the budget; each candidate is still handled at most once per run. 500 (owner, 2026-11-13; doc
 *     06 MVP-ADR-24 "Jobs (Q11)" amendment)
 */
@ConfigurationProperties(prefix = "app.transfer.expiry")
public record TransferExpiryProperties(boolean enabled, Duration interval, int maxPerRun) {

  private static final String PREFIX = "app.transfer.expiry.";

  public TransferExpiryProperties {
    if (interval == null || interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException(PREFIX + "interval must be positive");
    }
    if (maxPerRun < 1) {
      throw new IllegalArgumentException(PREFIX + "max-per-run must be at least 1");
    }
  }
}
