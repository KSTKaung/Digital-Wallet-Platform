package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.auth.domain.OtpPolicy;
import com.portfolio.wallet.auth.persistence.LoginFailureRepository;
import com.portfolio.wallet.auth.persistence.OtpChallengeRepository;
import com.portfolio.wallet.auth.persistence.PasswordResetTokenRepository;
import com.portfolio.wallet.auth.persistence.RefreshTokenRepository;
import com.portfolio.wallet.auth.persistence.SessionRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deletes what no rule needs any more (documentations/06 MVP-ADR-23). Each step is one statement
 * and fails alone: a failure is logged by exception class only, and the next run tries again.
 */
@Service
public class AuthPurgeService {

  private static final Logger log = LoggerFactory.getLogger(AuthPurgeService.class);

  private final LoginFailureRepository unknownFailures;
  private final OtpChallengeRepository challenges;
  private final RefreshTokenRepository refreshTokens;
  private final SessionRepository sessions;
  private final PasswordResetTokenRepository resetTokens;
  private final UserRepository users;
  private final OtpPolicy otpPolicy;
  private final AuthProperties properties;
  private final Clock clock;

  public AuthPurgeService(
      LoginFailureRepository unknownFailures,
      OtpChallengeRepository challenges,
      RefreshTokenRepository refreshTokens,
      SessionRepository sessions,
      PasswordResetTokenRepository resetTokens,
      UserRepository users,
      OtpPolicy otpPolicy,
      AuthProperties properties,
      Clock clock) {
    this.users = users;
    this.unknownFailures = unknownFailures;
    this.challenges = challenges;
    this.refreshTokens = refreshTokens;
    this.sessions = sessions;
    this.resetTokens = resetTokens;
    this.otpPolicy = otpPolicy;
    this.properties = properties;
    this.clock = clock;
  }

  /** Refresh tokens go before sessions, because a session waits for its last token to expire. */
  public void purgeExpired() {
    Instant now = clock.instant();
    step(
        "login_failure_counters",
        () ->
            unknownFailures.deleteExpired(properties.lockout().unknownIdentifierRetention(), now));
    step("users", () -> users.deleteExpiredPending(properties.pendingAccountTtl()));
    step("otp_challenges", () -> challenges.deleteExpired(otpPolicy.retainedSince(now), now));
    step("refresh_tokens", () -> refreshTokens.deleteExpired(now));
    step(
        "sessions", () -> sessions.deleteEnded(now.minus(properties.session().idleTimeout()), now));
    step("password_reset_tokens", () -> resetTokens.deleteExpired(now));
  }

  private static void step(String table, IntSupplier delete) {
    try {
      int deleted = delete.getAsInt();
      if (deleted > 0) {
        log.info("Purged expired rows: table={}, count={}", table, deleted);
      }
    } catch (RuntimeException e) {
      log.warn("Purge failed: table={}, failure={}", table, e.getClass().getSimpleName());
    }
  }
}
