package com.portfolio.wallet.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.audit.application.AccessDenialThrottle.Decision;
import com.portfolio.wallet.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The in-memory {@code 403} audit cap (owner, 2026-11-03; documentations/08 §4B.2 Q12). */
class AccessDenialThrottleTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final MutableClock clock = new MutableClock();

  @Test
  void theWindowStartsAtTheAuditedRefusalAndEndsAfterTheConfiguredDuration() {
    AccessDenialThrottle throttle = new AccessDenialThrottle(WINDOW, 10, clock);
    UUID user = UUID.randomUUID();

    assertThat(throttle.tryAcquire(user)).isEqualTo(Decision.AUDIT);
    clock.advance(Duration.ofSeconds(30));
    assertThat(throttle.tryAcquire(user)).isEqualTo(Decision.IN_WINDOW);
    // Refused refusals do not extend the window.
    clock.advance(Duration.ofSeconds(30));
    assertThat(throttle.tryAcquire(user)).isEqualTo(Decision.AUDIT);
  }

  @Test
  void aShorterConfiguredWindowIsHonoured() {
    AccessDenialThrottle throttle = new AccessDenialThrottle(Duration.ofSeconds(5), 10, clock);
    UUID user = UUID.randomUUID();

    assertThat(throttle.tryAcquire(user)).isEqualTo(Decision.AUDIT);
    clock.advance(Duration.ofSeconds(5));
    assertThat(throttle.tryAcquire(user)).isEqualTo(Decision.AUDIT);
  }

  @Test
  void whileFullAnUntrackedUserIsNotAuditedButATrackedOneKeepsItsWindow() {
    AccessDenialThrottle throttle = new AccessDenialThrottle(WINDOW, 2, clock);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    assertThat(throttle.tryAcquire(first)).isEqualTo(Decision.AUDIT);
    assertThat(throttle.tryAcquire(second)).isEqualTo(Decision.AUDIT);

    assertThat(throttle.tryAcquire(UUID.randomUUID())).isEqualTo(Decision.FULL);
    assertThat(throttle.tryAcquire(first)).isEqualTo(Decision.IN_WINDOW);
    assertThat(throttle.trackedUsers()).isEqualTo(2);
  }

  @Test
  void endedWindowsAreSweptSoMemoryStaysBounded() {
    AccessDenialThrottle throttle = new AccessDenialThrottle(WINDOW, 2, clock);
    throttle.tryAcquire(UUID.randomUUID());
    throttle.tryAcquire(UUID.randomUUID());

    clock.advance(WINDOW);
    assertThat(throttle.tryAcquire(UUID.randomUUID())).isEqualTo(Decision.AUDIT);
    assertThat(throttle.trackedUsers()).isEqualTo(1);
  }

  @Test
  void releaseEndsTheWindow() {
    AccessDenialThrottle throttle = new AccessDenialThrottle(WINDOW, 10, clock);
    UUID user = UUID.randomUUID();
    throttle.tryAcquire(user);

    throttle.release(user);

    assertThat(throttle.tryAcquire(user)).isEqualTo(Decision.AUDIT);
  }

  @Test
  void concurrentRefusalsOfOneUserAuditExactlyOnce() throws Exception {
    AccessDenialThrottle throttle = new AccessDenialThrottle(WINDOW, 10, clock);
    UUID user = UUID.randomUUID();
    AtomicInteger acquired = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        futures.add(
            pool.submit(
                () -> {
                  if (throttle.tryAcquire(user) == Decision.AUDIT) {
                    acquired.incrementAndGet();
                  }
                }));
      }
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(acquired).hasValue(1);
  }

  @Test
  void theConfigurationMustBePositive() {
    assertThatThrownBy(() -> new AuditProperties(null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuditProperties(Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuditProperties(Duration.ofSeconds(-1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuditProperties(WINDOW, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("app.audit.access-denied-max-tracked-users must be at least 1");
    assertThat(new AuditProperties(WINDOW, 1).accessDeniedWindow()).isEqualTo(WINDOW);
  }
}
