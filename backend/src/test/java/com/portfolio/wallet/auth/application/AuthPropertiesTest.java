package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.security.RateLimitProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The real application.yaml holds the documented values (documentations/06 MVP-ADR-23). */
class AuthPropertiesTest {

  @Configuration
  @EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class})
  static class Properties {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(Properties.class);

  @Test
  void applicationYamlHoldsTheDocumentedValues() {
    runner.run(
        context -> {
          AuthProperties auth = context.getBean(AuthProperties.class);

          assertThat(auth.lockout())
              .isEqualTo(
                  new AuthProperties.Lockout(5, Duration.ofMinutes(15), Duration.ofHours(24)));
          assertThat(auth.password()).isEqualTo(new AuthProperties.Password(12, 128, 5));
          assertThat(auth.argon2().memoryKib()).isEqualTo(19 * 1024);
          assertThat(auth.argon2().iterations()).isEqualTo(2);
          assertThat(auth.argon2().parallelism()).isEqualTo(1);
          assertThat(auth.argon2().maxConcurrentHashes()).isEqualTo(4);
          assertThat(auth.otp())
              .isEqualTo(
                  new AuthProperties.Otp(
                      6,
                      Duration.ofMinutes(5),
                      5,
                      Duration.ofSeconds(60),
                      5,
                      5,
                      Duration.ofHours(24)));
          assertThat(auth.token())
              .isEqualTo(
                  new AuthProperties.Token(
                      Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofMinutes(15)));
          assertThat(auth.session())
              .isEqualTo(new AuthProperties.Session(Duration.ofMinutes(30), Duration.ofMinutes(1)));

          RateLimitProperties limits = context.getBean(RateLimitProperties.class);
          assertThat(limits.authRequestsPerMinutePerIp()).isEqualTo(10);
          assertThat(limits.otherRequestsPerMinutePerUser()).isEqualTo(300);
          assertThat(limits.maxTrackedKeys()).isPositive();
          assertThat(auth.purge().enabled()).isTrue();
          assertThat(auth.purge().interval()).isPositive();
          assertThat(auth.pendingAccountTtl()).isEqualTo(Duration.ofHours(24));
          assertThat(auth.ownerNoticeInterval()).isEqualTo(Duration.ofHours(1));
        });
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "app.auth.lockout.max-failed-attempts=0",
        "app.auth.lockout.duration=0s",
        "app.auth.password.min-length=0",
        "app.auth.password.max-length=11",
        "app.auth.argon2.max-concurrent-hashes=0",
        "app.auth.otp.ttl=-1s",
        "app.auth.token.access-ttl=0s",
        "app.auth.session.idle-timeout=0s",
        "app.auth.purge.interval=0s",
        "app.auth.pending-account-ttl=0s",
        "app.auth.owner-notice-interval=0s",
        "app.rate-limit.auth-requests-per-minute-per-ip=0",
        "app.rate-limit.max-tracked-keys=0"
      })
  void invalidValueStopsTheContext(String property) {
    runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
  }
}
