package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.auth.domain.LockoutPolicy;
import com.portfolio.wallet.auth.domain.OtpPolicy;
import com.portfolio.wallet.auth.domain.PasswordPolicy;
import com.portfolio.wallet.auth.domain.SessionPolicy;
import com.portfolio.wallet.common.security.RequiredSecrets;
import com.portfolio.wallet.support.TestSecrets;
import java.time.Duration;

/** The documented values, with a cheap Argon2 cost so that unit tests stay fast. */
final class AuthTestFixtures {

  static final AuthProperties PROPERTIES =
      new AuthProperties(
          new AuthProperties.Lockout(5, Duration.ofMinutes(15), Duration.ofHours(24)),
          new AuthProperties.Password(12, 128, 5),
          new AuthProperties.Argon2(64, 1, 1, 16, 32, 2),
          new AuthProperties.Otp(
              6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5, 5, Duration.ofHours(24)),
          new AuthProperties.Token(
              Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofMinutes(15)),
          new AuthProperties.Session(Duration.ofMinutes(30), Duration.ofMinutes(1)),
          new AuthProperties.Purge(false, Duration.ofHours(1)),
          Duration.ofHours(24),
          Duration.ofHours(1));

  static final AuthConfiguration CONFIG = new AuthConfiguration();
  static final PasswordPolicy PASSWORDS = CONFIG.passwordPolicy(PROPERTIES);
  static final OtpPolicy OTP = CONFIG.otpPolicy(PROPERTIES);
  static final LockoutPolicy LOCKOUT = CONFIG.lockoutPolicy(PROPERTIES);
  static final SessionPolicy SESSION = CONFIG.sessionPolicy(PROPERTIES);
  static final RequiredSecrets SECRETS = RequiredSecrets.from(TestSecrets.values()::get);

  private AuthTestFixtures() {}
}
