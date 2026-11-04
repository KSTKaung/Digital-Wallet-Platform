package com.portfolio.wallet.notification.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The email outbox's retry policy and job settings (documentations/06 MVP-ADR-25). The MVP values
 * are 5 attempts, retried after about 1, 5, 15 and 60 minutes; they live in configuration. Checked
 * at startup.
 *
 * @param maxAttempts after this many failed attempts a row is {@code FAILED} and not retried
 * @param backoff the wait after failed attempt 1, 2, …; exactly one entry fewer than {@code
 *     maxAttempts}, because there is no wait after the last attempt
 * @param batchSize the most rows one run sends; not a documented value
 * @param pollInterval how often the scheduled job runs; not a documented value
 * @param lease how long a claimed row stays invisible to other runs while it is sent (N1): long
 *     enough for one lookup and one SMTP send; a row whose outcome was never recorded (a crash, a
 *     database failure) is claimed again after it; 5 minutes, not a documented value. At startup it
 *     must be longer than the mail timeouts of one send plus {@code leaseSafetyMargin} (R3, S1;
 *     {@link OutboxLeaseCheck})
 * @param leaseSafetyMargin how much longer than the mail timeouts the lease must be; 30 seconds, an
 *     MVP value
 * @param addressLookupTimeout the longest the address lookup of one send may run in the database
 *     (S1); a timeout fails that attempt. Whole seconds; 5 seconds, an MVP value
 */
@ConfigurationProperties(prefix = "app.notification.outbox")
public record OutboxProperties(
    int maxAttempts,
    List<Duration> backoff,
    int batchSize,
    Duration pollInterval,
    Duration lease,
    Duration leaseSafetyMargin,
    Duration addressLookupTimeout) {

  private static final String PREFIX = "app.notification.outbox.";

  public OutboxProperties {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException(PREFIX + "max-attempts must be at least 1");
    }
    if (backoff == null || backoff.size() != maxAttempts - 1) {
      throw new IllegalArgumentException(
          PREFIX + "backoff must have one entry fewer than max-attempts");
    }
    if (backoff.stream().anyMatch(wait -> wait == null || wait.isNegative() || wait.isZero())) {
      throw new IllegalArgumentException(PREFIX + "backoff entries must be positive");
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException(PREFIX + "batch-size must be at least 1");
    }
    if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException(PREFIX + "poll-interval must be positive");
    }
    if (lease == null || lease.isNegative() || lease.isZero()) {
      throw new IllegalArgumentException(PREFIX + "lease must be positive");
    }
    if (leaseSafetyMargin == null || leaseSafetyMargin.isNegative()) {
      throw new IllegalArgumentException(PREFIX + "lease-safety-margin must not be negative");
    }
    if (addressLookupTimeout == null || addressLookupTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalArgumentException(
          PREFIX
              + "address-lookup-timeout must be at least 1s (JDBC query timeouts are in seconds)");
    }
    backoff = List.copyOf(backoff);
  }

  /**
   * @param failedAttempts how many attempts have failed so far, from 1 to {@code maxAttempts - 1}
   * @return how long to wait before the next attempt
   */
  public Duration waitAfter(int failedAttempts) {
    return backoff.get(failedAttempts - 1);
  }
}
