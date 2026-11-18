package com.portfolio.wallet.admin.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The scheduled reconciliation ({@code app.reconciliation}; documentations/06 MVP-ADR-03,
 * MVP-ADR-24 "Jobs (Q11)"; doc 07 D18). Checked at startup; messages name the property, never a
 * value.
 *
 * @param enabled turns the scheduled job on; tests turn it off and call {@link
 *     ReconciliationService#reconcile()} directly
 * @param initialDelay how long after startup the first run starts: 5 minutes (owner, 2026-11-15).
 *     Positive and at most {@code interval}, so a restart never postpones a run by more than one
 *     interval
 * @param interval the fixed delay between the end of one run and the start of the next: hourly
 *     (Q11, MVP value)
 */
@ConfigurationProperties(prefix = "app.reconciliation")
public record ReconciliationProperties(boolean enabled, Duration initialDelay, Duration interval) {

  private static final String PREFIX = "app.reconciliation.";

  public ReconciliationProperties {
    if (interval == null || interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException(PREFIX + "interval must be positive");
    }
    if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) {
      throw new IllegalArgumentException(PREFIX + "initial-delay must be positive");
    }
    if (initialDelay.compareTo(interval) > 0) {
      throw new IllegalArgumentException(
          PREFIX + "initial-delay must not be longer than " + PREFIX + "interval");
    }
  }
}
