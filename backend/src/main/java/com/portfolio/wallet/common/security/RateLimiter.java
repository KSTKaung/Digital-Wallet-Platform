package com.portfolio.wallet.common.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fixed window per key, starting at the key's first request. In memory, so it is correct for
 * <strong>one instance only</strong> and resets on restart (documentations/06 MVP-ADR-06); more
 * than one instance needs a distributed limiter first.
 *
 * <p>Memory is bounded: ended windows are swept, and at most {@code maxKeys} are tracked, give or
 * take concurrent inserts. While that many are live, a new key is refused rather than let in
 * uncounted: evicting a live window would hand an attacker a way to reset its own count.
 *
 * <p>Thread-safe. Each update is one short atomic {@code compute} and nothing blocks in it.
 */
final class RateLimiter {

  private static final long MILLIS_PER_SECOND = 1000;
  private static final int SWEEPS_PER_WINDOW = 10;

  /**
   * @param retryAfterSeconds when a refused caller may try again; 0 when allowed
   */
  record Decision(boolean allowed, long retryAfterSeconds) {
    private static final Decision ALLOWED = new Decision(true, 0);
  }

  private record Window(long startMillis, int count) {}

  private final int limit;
  private final long windowMillis;
  private final long sweepIntervalMillis;
  private final int maxKeys;
  private final Clock clock;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final AtomicLong nextSweepMillis;

  RateLimiter(int limit, Duration window, int maxKeys, Clock clock) {
    this.limit = limit;
    this.windowMillis = window.toMillis();
    this.sweepIntervalMillis = Math.max(1, windowMillis / SWEEPS_PER_WINDOW);
    this.maxKeys = maxKeys;
    this.clock = clock;
    this.nextSweepMillis = new AtomicLong(clock.millis() + sweepIntervalMillis);
  }

  Decision tryAcquire(String key) {
    long now = clock.millis();
    sweepIfDue(now);
    if (windows.size() >= maxKeys && !windows.containsKey(key)) {
      return refusedFor(nextSweepMillis.get() - now);
    }
    Window window =
        windows.compute(
            key,
            (k, current) ->
                current == null || hasEnded(current, now)
                    ? new Window(now, 1)
                    // Stops at limit + 1, so a flood cannot overflow the count.
                    : new Window(current.startMillis(), Math.min(current.count() + 1, limit + 1)));
    return window.count() <= limit
        ? Decision.ALLOWED
        : refusedFor(window.startMillis() + windowMillis - now);
  }

  int trackedKeys() {
    return windows.size();
  }

  private boolean hasEnded(Window window, long now) {
    return now - window.startMillis() >= windowMillis;
  }

  // One caller per interval does the sweep; the others carry on without waiting.
  private void sweepIfDue(long now) {
    long due = nextSweepMillis.get();
    if (now >= due && nextSweepMillis.compareAndSet(due, now + sweepIntervalMillis)) {
      windows.values().removeIf(window -> hasEnded(window, now));
    }
  }

  private static Decision refusedFor(long remainingMillis) {
    long seconds = (remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND;
    return new Decision(false, Math.max(1, seconds));
  }
}
