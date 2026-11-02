package com.portfolio.wallet.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LockoutAndSessionPolicyTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

  private final LockoutPolicy lockout = new LockoutPolicy(5, Duration.ofMinutes(15));
  private final SessionPolicy session =
      new SessionPolicy(Duration.ofMinutes(30), Duration.ofMinutes(1));

  @Test
  void aLockHoldsUntilItsInstant() {
    assertThat(lockout.isLocked(null, NOW)).isFalse();
    assertThat(lockout.isLocked(NOW.plusSeconds(1), NOW)).isTrue();
    assertThat(lockout.isLocked(NOW, NOW)).isFalse();
    assertThat(lockout.lockedUntil(NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
  }

  @Test
  void idleBoundary() {
    assertThat(session.isIdle(NOW.minus(Duration.ofMinutes(30)).plusSeconds(1), NOW)).isFalse();
    assertThat(session.isIdle(NOW.minus(Duration.ofMinutes(30)), NOW)).isTrue();
  }

  @Test
  void lastSeenWriteBoundary() {
    assertThat(session.isLastSeenWriteDue(NOW.minusSeconds(59), NOW)).isFalse();
    assertThat(session.isLastSeenWriteDue(NOW.minusSeconds(60), NOW)).isTrue();
    assertThat(session.lastSeenWriteCutoff(NOW)).isEqualTo(NOW.minusSeconds(60));
  }
}
