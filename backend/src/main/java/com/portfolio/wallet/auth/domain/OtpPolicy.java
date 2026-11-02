package com.portfolio.wallet.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One-time code limits (documentations/06 MVP-ADR-23). The counts are read from the database, so
 * they survive a restart.
 */
public record OtpPolicy(
    int digits,
    Duration ttl,
    int maxAttempts,
    Duration resendCooldown,
    int maxResendsPerHour,
    int usedUpCap,
    Duration usedUpWindow) {

  private static final Duration HOUR = Duration.ofHours(1);

  public Instant expiresAt(Instant sentAt) {
    return sentAt.plus(ttl);
  }

  public Instant hourlyWindowStart(Instant now) {
    return now.minus(HOUR);
  }

  public Instant usedUpWindowStart(Instant now) {
    return now.minus(usedUpWindow);
  }

  /** A challenge sent or used up before this is outside every window a limit looks at. */
  public Instant retainedSince(Instant now) {
    Duration longest = usedUpWindow.compareTo(HOUR) > 0 ? usedUpWindow : HOUR;
    return now.minus(longest.compareTo(resendCooldown) > 0 ? longest : resendCooldown);
  }

  /** The strictest limit that applies; the 24-hour block outranks the shorter ones. */
  public OtpIssueDecision decide(OtpIssueHistory history, Instant now) {
    if (history.usedUpInWindow() >= usedUpCap
        || history.wrongGuessesInWindow() >= (long) usedUpCap * maxAttempts) {
      return OtpIssueDecision.USED_UP_BLOCK;
    }
    if (history.lastSentAt() != null && history.lastSentAt().plus(resendCooldown).isAfter(now)) {
      return OtpIssueDecision.COOLDOWN;
    }
    return resendsInLastHour(history, now) >= maxResendsPerHour
        ? OtpIssueDecision.HOURLY_CAP
        : OtpIssueDecision.ALLOWED;
  }

  /** Why a stored challenge cannot accept a code at all, before the code is looked at. */
  public Optional<OtpFailure> refusal(OtpChallengeState challenge, Instant now) {
    if (challenge.consumedAt() != null) {
      return Optional.of(OtpFailure.ALREADY_CONSUMED);
    }
    if (challenge.cancelledAt() != null) {
      return Optional.of(OtpFailure.CANCELLED);
    }
    if (challenge.usedUpAt() != null) {
      return Optional.of(OtpFailure.USED_UP);
    }
    return now.isBefore(challenge.expiresAt()) ? Optional.empty() : Optional.of(OtpFailure.EXPIRED);
  }

  /**
   * Whether the wrong guess that brings the count to {@code attemptsAfter} spends the challenge.
   */
  public boolean isUsedUp(int attemptsAfter) {
    return attemptsAfter >= maxAttempts;
  }

  // The first send is not a resend. It counts only while it is still inside the window.
  private long resendsInLastHour(OtpIssueHistory history, Instant now) {
    boolean firstInWindow =
        history.firstSentAt() != null && history.firstSentAt().isAfter(hourlyWindowStart(now));
    return history.sendsInLastHour() - (firstInWindow ? 1 : 0);
  }
}
