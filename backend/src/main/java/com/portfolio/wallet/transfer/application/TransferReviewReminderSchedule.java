package com.portfolio.wallet.transfer.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the 12-hour review reminder on the configured interval, every 1 minute (documentations/06
 * MVP-ADR-24 "Jobs (Q11)"). Per instance; the deterministic event id keeps it correct with more
 * than one. Tests turn it off and call {@link TransferReviewReminderService} directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.transfer.review-reminder.enabled", havingValue = "true")
public class TransferReviewReminderSchedule {

  private final TransferReviewReminderService reminders;

  public TransferReviewReminderSchedule(TransferReviewReminderService reminders) {
    this.reminders = reminders;
  }

  @Scheduled(
      initialDelayString = "${app.transfer.review-reminder.interval}",
      fixedDelayString = "${app.transfer.review-reminder.interval}")
  void run() {
    reminders.remindDue();
  }
}
