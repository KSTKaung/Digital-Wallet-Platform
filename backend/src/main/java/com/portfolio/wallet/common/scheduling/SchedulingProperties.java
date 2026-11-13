package com.portfolio.wallet.common.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The scheduler that runs every {@code @Scheduled} job ({@code app.scheduling}). Checked when the
 * application starts; messages name the property, never a value.
 *
 * @param poolSize how many jobs can run at the same time: 2 (owner decision on the PR 5a step 6
 *     security review, L2: the email outbox keeps sending while a long expiry run is in progress).
 *     At least 1. A job never overlaps itself whatever the size (fixed delay)
 * @param awaitTermination how long shutdown waits for running jobs before it stops them, as Boot's
 *     {@code spring.task.scheduling.shutdown.await-termination-period}; zero stops at once. Not a
 *     documented value
 */
@ConfigurationProperties(prefix = "app.scheduling")
public record SchedulingProperties(int poolSize, Duration awaitTermination) {

  private static final String PREFIX = "app.scheduling.";

  public SchedulingProperties {
    if (poolSize < 1) {
      throw new IllegalArgumentException(PREFIX + "pool-size must be at least 1");
    }
    if (awaitTermination == null || awaitTermination.isNegative()) {
      throw new IllegalArgumentException(PREFIX + "await-termination must not be negative");
    }
  }
}
