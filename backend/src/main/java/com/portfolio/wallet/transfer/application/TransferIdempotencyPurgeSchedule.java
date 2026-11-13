package com.portfolio.wallet.transfer.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the transfer idempotency purge on the configured interval (documentations/07 D04). Per
 * instance; {@code SKIP LOCKED} keeps it correct with more than one. Tests turn it off and call
 * {@link TransferIdempotencyPurgeService} directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.transfer.idempotency.purge.enabled", havingValue = "true")
public class TransferIdempotencyPurgeSchedule {

  private final TransferIdempotencyPurgeService purge;

  public TransferIdempotencyPurgeSchedule(TransferIdempotencyPurgeService purge) {
    this.purge = purge;
  }

  @Scheduled(
      initialDelayString = "${app.transfer.idempotency.purge.interval}",
      fixedDelayString = "${app.transfer.idempotency.purge.interval}")
  void run() {
    purge.purgeExpired();
  }
}
