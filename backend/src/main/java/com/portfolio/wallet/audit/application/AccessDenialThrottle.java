package com.portfolio.wallet.audit.application;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * At most one audited {@code ACCESS_DENIED} per user per window (owner, 2026-11-03;
 * documentations/08 §4B.2 Q12). The window starts at the audited refusal; refusals inside it are
 * not audited.
 *
 * <p>In memory, so the cap holds <strong>per instance</strong> and starts afresh on restart: after
 * a restart a user's next refusal is audited again, and with N instances a user can produce up to N
 * rows per window. A shared store (for example Redis) is the target for more than one instance (doc
 * 06 MVP-ADR-23, open risks).
 *
 * <p>Memory is bounded: ended windows are swept, and at most {@code maxUsers} are tracked, give or
 * take concurrent inserts. While that many windows are live, a refusal by an untracked user is not
 * audited (it is still logged), so a flood of accounts cannot reopen the append-only table; the
 * same choice as the rate limiter, which refuses rather than evicts.
 *
 * <p>Thread-safe: one short atomic {@code compute} per refusal.
 */
final class AccessDenialThrottle {

  private static final int SWEEPS_PER_WINDOW = 10;

  private final long windowMillis;
  private final long sweepIntervalMillis;
  private final int maxUsers;
  private final Clock clock;
  private final ConcurrentHashMap<UUID, Long> windowStarts = new ConcurrentHashMap<>();
  private final AtomicLong nextSweepMillis;

  AccessDenialThrottle(Duration window, int maxUsers, Clock clock) {
    this.windowMillis = window.toMillis();
    this.sweepIntervalMillis = Math.max(1, windowMillis / SWEEPS_PER_WINDOW);
    this.maxUsers = maxUsers;
    this.clock = clock;
    this.nextSweepMillis = new AtomicLong(clock.millis() + sweepIntervalMillis);
  }

  /** What happens to a refusal. */
  enum Decision {
    /** Audit it; a new window has started. */
    AUDIT,
    /** Not audited: the user's audit row for the current window exists. */
    IN_WINDOW,
    /** Not audited: the cap tracks its maximum number of users and this one is not among them. */
    FULL
  }

  /** Whether this user's refusal is audited now; if so, a new window starts. */
  Decision tryAcquire(UUID userId) {
    long now = clock.millis();
    sweepIfDue(now);
    if (windowStarts.size() >= maxUsers && !windowStarts.containsKey(userId)) {
      return Decision.FULL;
    }
    boolean[] acquired = {false};
    windowStarts.compute(
        userId,
        (id, start) -> {
          if (start == null || hasEnded(start, now)) {
            acquired[0] = true;
            return now;
          }
          return start;
        });
    return acquired[0] ? Decision.AUDIT : Decision.IN_WINDOW;
  }

  /** Ends the user's window, for an audit row that could not be written. */
  void release(UUID userId) {
    windowStarts.remove(userId);
  }

  int trackedUsers() {
    return windowStarts.size();
  }

  private boolean hasEnded(long start, long now) {
    return now - start >= windowMillis;
  }

  // One caller per interval does the sweep; the others carry on without waiting.
  private void sweepIfDue(long now) {
    long due = nextSweepMillis.get();
    if (now >= due && nextSweepMillis.compareAndSet(due, now + sweepIntervalMillis)) {
      windowStarts.values().removeIf(start -> hasEnded(start, now));
    }
  }
}
