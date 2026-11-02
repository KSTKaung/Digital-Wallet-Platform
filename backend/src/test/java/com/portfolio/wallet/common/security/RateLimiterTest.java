package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  private static final Duration MINUTE = Duration.ofMinutes(1);

  private final MutableClock clock = new MutableClock();

  private RateLimiter limiter(int limit, int maxKeys) {
    return new RateLimiter(limit, MINUTE, maxKeys, clock);
  }

  @Test
  void theLimitIsAllowedAndTheNextIsRefusedUntilTheWindowEnds() {
    RateLimiter limiter = limiter(3, 10);
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }
    assertThat(limiter.tryAcquire("a").allowed()).isFalse();

    clock.advance(MINUTE.minusMillis(1));
    assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    clock.advance(Duration.ofMillis(1));
    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
  }

  @Test
  void retryAfterIsTheRestOfTheWindowRoundedUp() {
    RateLimiter limiter = limiter(1, 10);
    limiter.tryAcquire("a");
    assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isEqualTo(60);
    clock.advance(Duration.ofMillis(59_500));
    assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  void refusedRequestsDoNotExtendTheWindow() {
    RateLimiter limiter = limiter(1, 10);
    limiter.tryAcquire("a");
    clock.advance(Duration.ofSeconds(30));
    limiter.tryAcquire("a");
    clock.advance(Duration.ofSeconds(30));
    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
  }

  @Test
  void keysAreIndependent() {
    RateLimiter limiter = limiter(1, 10);
    limiter.tryAcquire("a");
    assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    assertThat(limiter.tryAcquire("b").allowed()).isTrue();
  }

  @Test
  void endedWindowsAreEvicted() {
    RateLimiter limiter = limiter(5, 1000);
    for (int i = 0; i < 100; i++) {
      limiter.tryAcquire("key-" + i);
    }
    assertThat(limiter.trackedKeys()).isEqualTo(100);

    clock.advance(MINUTE);
    limiter.tryAcquire("later");

    assertThat(limiter.trackedKeys()).isEqualTo(1);
  }

  @Test
  void rotatingKeysCannotGrowTheMapAndANewKeyIsRefusedWhileItIsFull() {
    RateLimiter limiter = limiter(5, 10);
    for (int i = 0; i < 10_000; i++) {
      limiter.tryAcquire("key-" + i);
    }
    assertThat(limiter.trackedKeys()).isEqualTo(10);

    RateLimiter.Decision refused = limiter.tryAcquire("new");
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.retryAfterSeconds()).isPositive();
    // A key already tracked is still served.
    assertThat(limiter.tryAcquire("key-0").allowed()).isTrue();

    clock.advance(MINUTE);
    assertThat(limiter.tryAcquire("new").allowed()).isTrue();
  }

  @Test
  void concurrentRequestsCannotPassTheLimitTogether() throws Exception {
    RateLimiter limiter = limiter(100, 10);
    List<Future<Boolean>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 1000; i++) {
        results.add(pool.submit(() -> limiter.tryAcquire("a").allowed()));
      }
    }
    int allowed = 0;
    for (Future<Boolean> result : results) {
      allowed += result.get() ? 1 : 0;
    }
    assertThat(allowed).isEqualTo(100);
  }
}
