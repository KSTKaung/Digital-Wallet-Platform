package com.portfolio.wallet.transfer.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The reminder job of held transfers ({@code app.transfer.review-reminder}; documentations/06
 * MVP-ADR-24 "Expiry", "Jobs (Q11)"; doc 08 §4C.2 Q11, §4C.4 criteria 12, 16). When it reminds is
 * {@link TransferReviewProperties#reminderAfter()}. Checked at startup.
 *
 * @param enabled turns the scheduled job on; tests turn it off and call {@link
 *     TransferReviewReminderService#remindDue()} directly
 * @param interval how often the job runs: every 1 minute (Q11, MVP value)
 * @param maxPerRun the most transfers one run writes reminders for, so one run stays bounded; the
 *     rest wait for the next run. A transfer that was already reminded, is skipped or fails does
 *     not count, so they cannot use up the budget; each candidate is still handled at most once per
 *     run
 */
@ConfigurationProperties(prefix = "app.transfer.review-reminder")
public record TransferReviewReminderProperties(boolean enabled, Duration interval, int maxPerRun) {

  private static final String PREFIX = "app.transfer.review-reminder.";

  public TransferReviewReminderProperties {
    if (interval == null || interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException(PREFIX + "interval must be positive");
    }
    if (maxPerRun < 1) {
      throw new IllegalArgumentException(PREFIX + "max-per-run must be at least 1");
    }
  }
}
