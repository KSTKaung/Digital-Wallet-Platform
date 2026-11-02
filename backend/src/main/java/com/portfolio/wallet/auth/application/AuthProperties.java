package com.portfolio.wallet.auth.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Authentication parameters (documentations/06 MVP-ADR-23). The values live in {@code
 * application.yaml}, never in code; an invalid value stops the application at startup.
 *
 * @param pendingAccountTtl how long an account nobody verified is kept, from its creation
 * @param ownerNoticeInterval at most one "someone tried to register" email per owner in this time
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    @NotNull @Valid Lockout lockout,
    @NotNull @Valid Password password,
    @NotNull @Valid Argon2 argon2,
    @NotNull @Valid Otp otp,
    @NotNull @Valid Token token,
    @NotNull @Valid Session session,
    @NotNull @Valid Purge purge,
    @NotNull @DurationMin(nanos = 1) Duration pendingAccountTtl,
    @NotNull @DurationMin(nanos = 1) Duration ownerNoticeInterval) {

  /**
   * @param enabled whether the scheduled purge runs; {@code interval} is read by its annotation
   */
  public record Purge(boolean enabled, @NotNull @DurationMin(nanos = 1) Duration interval) {}

  /**
   * @param unknownIdentifierRetention how long counters for unknown emails are kept
   */
  public record Lockout(
      @Positive int maxFailedAttempts,
      @NotNull @DurationMin(nanos = 1) Duration duration,
      @NotNull @DurationMin(nanos = 1) Duration unknownIdentifierRetention) {}

  /**
   * @param minLength in Unicode code points after NFC normalisation, like {@code maxLength}
   * @param historyDepth how many previous passwords may not be reused
   */
  public record Password(
      @Positive int minLength, @Positive int maxLength, @Positive int historyDepth) {

    @AssertTrue(message = "max-length must not be below min-length")
    public boolean isLengthRangeValid() {
      return maxLength >= minLength;
    }
  }

  /**
   * @param maxConcurrentHashes hashes computed at the same time, which bounds the memory they use
   */
  public record Argon2(
      @Positive int memoryKib,
      @Positive int iterations,
      @Positive int parallelism,
      @Positive int saltLengthBytes,
      @Positive int hashLengthBytes,
      @Positive int maxConcurrentHashes) {}

  /**
   * @param usedUpCap used-up challenges within {@code usedUpWindow} that block new challenges
   */
  public record Otp(
      @Positive int digits,
      @NotNull @DurationMin(nanos = 1) Duration ttl,
      @Positive int maxAttempts,
      @NotNull @DurationMin(nanos = 1) Duration resendCooldown,
      @Positive int maxResendsPerHour,
      @Positive int usedUpCap,
      @NotNull @DurationMin(nanos = 1) Duration usedUpWindow) {}

  /**
   * @param resetTtl lifetime of the single-use password-reset token
   */
  public record Token(
      @NotNull @DurationMin(nanos = 1) Duration accessTtl,
      @NotNull @DurationMin(nanos = 1) Duration refreshTtl,
      @NotNull @DurationMin(nanos = 1) Duration resetTtl) {}

  /**
   * @param lastSeenWriteInterval {@code last_seen_at} is written at most this often
   */
  public record Session(
      @NotNull @DurationMin(nanos = 1) Duration idleTimeout,
      @NotNull @DurationMin(nanos = 1) Duration lastSeenWriteInterval) {}
}
