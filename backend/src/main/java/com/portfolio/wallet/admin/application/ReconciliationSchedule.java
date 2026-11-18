package com.portfolio.wallet.admin.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the reconciliation first {@code app.reconciliation.initial-delay} after startup (5 minutes),
 * then on the configured interval, hourly, counted from the end of each run (documentations/06
 * MVP-ADR-24 "Jobs (Q11)"; owner, 2026-11-15). Per instance: it only reads and logs, so several
 * instances are safe (each logs what it finds). {@link ReconciliationService#reconcile()} logs a
 * failed run and returns; it throws only a {@link VirtualMachineError} (after logging it), and
 * refuses to start inside a transaction, which the scheduler thread never has. Tests turn it off
 * and call the service directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.reconciliation.enabled", havingValue = "true")
public class ReconciliationSchedule {

  private final ReconciliationService reconciliation;

  public ReconciliationSchedule(ReconciliationService reconciliation) {
    this.reconciliation = reconciliation;
  }

  @Scheduled(
      initialDelayString = "${app.reconciliation.initial-delay}",
      fixedDelayString = "${app.reconciliation.interval}")
  void run() {
    reconciliation.reconcile();
  }
}
