package com.portfolio.wallet.auth.domain;

import java.time.Duration;
import java.time.Instant;

/** Session inactivity rules (documentations/06 MVP-ADR-23). */
public record SessionPolicy(Duration idleTimeout, Duration lastSeenWriteInterval) {

  /** A session idle for exactly the timeout has ended. */
  public boolean isIdle(Instant lastSeenAt, Instant now) {
    return !lastSeenAt.plus(idleTimeout).isAfter(now);
  }

  public boolean isLastSeenWriteDue(Instant lastSeenAt, Instant now) {
    return !lastSeenAt.plus(lastSeenWriteInterval).isAfter(now);
  }

  /** {@code last_seen_at} at or before this instant may be overwritten. */
  public Instant lastSeenWriteCutoff(Instant now) {
    return now.minus(lastSeenWriteInterval);
  }
}
