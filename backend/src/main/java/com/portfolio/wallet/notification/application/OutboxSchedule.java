package com.portfolio.wallet.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the outbox dispatcher on the configured interval (documentations/06 MVP-ADR-25). Per
 * instance; {@code SKIP LOCKED} keeps it correct with more than one. Tests turn it off and call
 * {@link OutboxDispatcher} directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.notification.outbox.enabled", havingValue = "true")
public class OutboxSchedule {

  private final OutboxDispatcher dispatcher;

  public OutboxSchedule(OutboxDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(
      initialDelayString = "${app.notification.outbox.poll-interval}",
      fixedDelayString = "${app.notification.outbox.poll-interval}")
  void run() {
    dispatcher.dispatchDue();
  }
}
