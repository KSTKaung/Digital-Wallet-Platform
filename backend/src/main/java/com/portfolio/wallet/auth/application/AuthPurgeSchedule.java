package com.portfolio.wallet.auth.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the purge on the configured interval; tests turn it off and call the service directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.auth.purge.enabled", havingValue = "true")
public class AuthPurgeSchedule {

  private final AuthPurgeService purge;

  public AuthPurgeSchedule(AuthPurgeService purge) {
    this.purge = purge;
  }

  @Scheduled(
      initialDelayString = "${app.auth.purge.interval}",
      fixedDelayString = "${app.auth.purge.interval}")
  void run() {
    purge.purgeExpired();
  }
}
