package com.portfolio.wallet.transfer.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the expiry of held transfers on the configured interval, every 1 minute (documentations/06
 * MVP-ADR-24 "Jobs (Q11)"). Per instance; {@code SKIP LOCKED} and the status check under the row
 * lock keep it correct with more than one. Tests turn it off and call {@link TransferExpiryService}
 * directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.transfer.expiry.enabled", havingValue = "true")
public class TransferExpirySchedule {

  private final TransferExpiryService expiry;

  public TransferExpirySchedule(TransferExpiryService expiry) {
    this.expiry = expiry;
  }

  @Scheduled(
      initialDelayString = "${app.transfer.expiry.interval}",
      fixedDelayString = "${app.transfer.expiry.interval}")
  void run() {
    expiry.expireDue();
  }
}
