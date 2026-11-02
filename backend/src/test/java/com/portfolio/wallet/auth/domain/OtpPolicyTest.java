package com.portfolio.wallet.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OtpPolicyTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

  private final OtpPolicy policy =
      new OtpPolicy(
          6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5, 5, Duration.ofHours(24));

  private static OtpChallengeState live() {
    return new OtpChallengeState(0, NOW.plusSeconds(1), null, null, null);
  }

  private OtpIssueDecision decide(Instant first, Instant last, long sends, long usedUp) {
    return policy.decide(new OtpIssueHistory(first, last, sends, usedUp, 0), NOW);
  }

  /** The cap times the attempts per challenge, however the challenges ended. */
  @org.junit.jupiter.api.Test
  void enoughWrongGuessesBlockEvenWithNoChallengeUsedUp() {
    org.assertj.core.api.Assertions.assertThat(
            policy.decide(new OtpIssueHistory(null, null, 0, 0, 24), NOW))
        .isEqualTo(OtpIssueDecision.ALLOWED);
    org.assertj.core.api.Assertions.assertThat(
            policy.decide(new OtpIssueHistory(null, null, 0, 0, 25), NOW))
        .isEqualTo(OtpIssueDecision.USED_UP_BLOCK);
  }

  @Test
  void aFirstChallengeIsAllowed() {
    assertThat(decide(null, null, 0, 0)).isEqualTo(OtpIssueDecision.ALLOWED);
  }

  @Test
  void cooldownBoundary() {
    Instant old = NOW.minus(Duration.ofDays(2));
    assertThat(decide(old, NOW.minusSeconds(59), 1, 0)).isEqualTo(OtpIssueDecision.COOLDOWN);
    assertThat(decide(old, NOW.minusSeconds(60), 1, 0)).isEqualTo(OtpIssueDecision.ALLOWED);
  }

  @Test
  void theFirstSendInTheWindowIsNotCountedAsAResend() {
    Instant recent = NOW.minus(Duration.ofMinutes(30));
    Instant last = NOW.minusSeconds(61);
    assertThat(decide(recent, last, 5, 0)).isEqualTo(OtpIssueDecision.ALLOWED);
    assertThat(decide(recent, last, 6, 0)).isEqualTo(OtpIssueDecision.HOURLY_CAP);
  }

  @Test
  void sendsInTheWindowAreAllResendsOnceTheFirstHasLeftIt() {
    Instant old = NOW.minus(Duration.ofHours(2));
    Instant last = NOW.minusSeconds(61);
    assertThat(decide(old, last, 4, 0)).isEqualTo(OtpIssueDecision.ALLOWED);
    assertThat(decide(old, last, 5, 0)).isEqualTo(OtpIssueDecision.HOURLY_CAP);
  }

  @Test
  void theUsedUpBlockOutranksTheOtherLimits() {
    assertThat(decide(NOW, NOW, 9, 5)).isEqualTo(OtpIssueDecision.USED_UP_BLOCK);
    assertThat(decide(null, null, 0, 4)).isEqualTo(OtpIssueDecision.ALLOWED);
  }

  @Test
  void windowsAndExpiryComeFromTheConfiguration() {
    assertThat(policy.expiresAt(NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    assertThat(policy.hourlyWindowStart(NOW)).isEqualTo(NOW.minus(Duration.ofHours(1)));
    assertThat(policy.usedUpWindowStart(NOW)).isEqualTo(NOW.minus(Duration.ofHours(24)));
  }

  @Test
  void aLiveChallengeHasNoRefusal() {
    assertThat(policy.refusal(live(), NOW)).isEmpty();
  }

  @Test
  void refusalReasons() {
    assertThat(policy.refusal(new OtpChallengeState(0, NOW.plusSeconds(9), NOW, null, null), NOW))
        .contains(OtpFailure.ALREADY_CONSUMED);
    assertThat(policy.refusal(new OtpChallengeState(0, NOW.plusSeconds(9), null, NOW, null), NOW))
        .contains(OtpFailure.CANCELLED);
    assertThat(policy.refusal(new OtpChallengeState(5, NOW.plusSeconds(9), null, null, NOW), NOW))
        .contains(OtpFailure.USED_UP);
    assertThat(policy.refusal(new OtpChallengeState(0, NOW, null, null, null), NOW))
        .contains(OtpFailure.EXPIRED);
  }

  @Test
  void attemptsBoundary() {
    assertThat(policy.isUsedUp(4)).isFalse();
    assertThat(policy.isUsedUp(5)).isTrue();
  }
}
