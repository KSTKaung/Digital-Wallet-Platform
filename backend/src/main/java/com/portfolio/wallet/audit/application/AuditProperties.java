package com.portfolio.wallet.audit.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the audit module.
 *
 * @param accessDeniedWindow at most one {@code ACCESS_DENIED} row per user in this window (owner,
 *     2026-11-03; documentations/08 §4B.2 Q12: one minute)
 * @param accessDeniedMaxTrackedUsers memory bound of the cap; not a documented value
 */
@ConfigurationProperties(prefix = "app.audit")
public record AuditProperties(Duration accessDeniedWindow, int accessDeniedMaxTrackedUsers) {

  public AuditProperties {
    if (accessDeniedWindow == null
        || accessDeniedWindow.isNegative()
        || accessDeniedWindow.isZero()) {
      throw new IllegalArgumentException("app.audit.access-denied-window must be positive");
    }
    if (accessDeniedMaxTrackedUsers < 1) {
      throw new IllegalArgumentException(
          "app.audit.access-denied-max-tracked-users must be at least 1");
    }
  }
}
