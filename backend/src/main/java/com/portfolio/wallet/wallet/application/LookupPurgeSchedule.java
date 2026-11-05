package com.portfolio.wallet.wallet.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the lookup-attempt purge on the configured interval. Per instance; two instances purging at
 * once only delete the same expired rows. Tests turn it off and call {@link LookupPurgeService}
 * directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.wallet.lookup.purge.enabled", havingValue = "true")
public class LookupPurgeSchedule {

  private final LookupPurgeService purge;

  public LookupPurgeSchedule(LookupPurgeService purge) {
    this.purge = purge;
  }

  @Scheduled(
      initialDelayString = "${app.wallet.lookup.purge.interval}",
      fixedDelayString = "${app.wallet.lookup.purge.interval}")
  void run() {
    purge.purgeExpired();
  }
}
