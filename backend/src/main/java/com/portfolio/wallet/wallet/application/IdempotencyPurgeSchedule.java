package com.portfolio.wallet.wallet.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the idempotency purge on the configured interval (documentations/07 D04). Per instance;
 * {@code SKIP LOCKED} keeps it correct with more than one. Tests turn it off and call {@link
 * IdempotencyPurgeService} directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.wallet.idempotency.purge.enabled", havingValue = "true")
public class IdempotencyPurgeSchedule {

  private final IdempotencyPurgeService purge;

  public IdempotencyPurgeSchedule(IdempotencyPurgeService purge) {
    this.purge = purge;
  }

  @Scheduled(
      initialDelayString = "${app.wallet.idempotency.purge.interval}",
      fixedDelayString = "${app.wallet.idempotency.purge.interval}")
  void run() {
    purge.purgeExpired();
  }
}
