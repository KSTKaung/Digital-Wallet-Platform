package com.portfolio.wallet.auth.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Login lockout (documentations/06 MVP-ADR-23). The counting itself is one atomic SQL statement,
 * which takes these values, so concurrent failures cannot be lost.
 */
public record LockoutPolicy(int maxFailedAttempts, Duration duration) {

  public boolean isLocked(Instant lockedUntil, Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  public Instant lockedUntil(Instant now) {
    return now.plus(duration);
  }
}
