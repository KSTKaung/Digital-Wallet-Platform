package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.auth.persistence.LoginFailureRepository;
import com.portfolio.wallet.auth.persistence.OtpChallengeRepository;
import com.portfolio.wallet.auth.persistence.PasswordResetTokenRepository;
import com.portfolio.wallet.auth.persistence.RefreshTokenRepository;
import com.portfolio.wallet.auth.persistence.SessionRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AuthPurgeServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

  private final LoginFailureRepository counters = mock(LoginFailureRepository.class);
  private final OtpChallengeRepository challenges = mock(OtpChallengeRepository.class);
  private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final AuthPurgeService service =
      new AuthPurgeService(
          counters,
          challenges,
          refreshTokens,
          sessions,
          resetTokens,
          users,
          AuthTestFixtures.OTP,
          AuthTestFixtures.PROPERTIES,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void eachStepUsesItsOwnRetention() {
    service.purgeExpired();

    Instant dayAgo = NOW.minus(Duration.ofHours(24));
    verify(counters).deleteExpired(Duration.ofHours(24), NOW);
    verify(users).deleteExpiredPending(Duration.ofHours(24));
    verify(challenges).deleteExpired(dayAgo, NOW);
    verify(sessions).deleteEnded(NOW.minus(Duration.ofMinutes(30)), NOW);
    verify(resetTokens).deleteExpired(NOW);
    InOrder order = Mockito.inOrder(refreshTokens, sessions);
    order.verify(refreshTokens).deleteExpired(NOW);
    order.verify(sessions).deleteEnded(any(), any());
  }

  @Test
  void aFailedStepIsLoggedByClassAndTheOthersStillRun(CapturedOutput output) {
    when(counters.deleteExpired(any(), any()))
        .thenThrow(new IllegalStateException("secret-detail"));
    when(resetTokens.deleteExpired(NOW)).thenReturn(2);

    service.purgeExpired();

    verify(challenges).deleteExpired(any(), any());
    verify(resetTokens).deleteExpired(NOW);
    assertThat(output.getAll()).contains("IllegalStateException").doesNotContain("secret-detail");
  }

  @Test
  void theScheduleRunsOnTheConfiguredIntervalOnlyWhenEnabled() {
    AuthPurgeService purge = mock(AuthPurgeService.class);
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(AuthPurgeService.class, () -> purge)
            .withUserConfiguration(AuthPurgeSchedule.class)
            .withPropertyValues("app.auth.purge.interval=10ms");

    runner
        .withPropertyValues("app.auth.purge.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(AuthPurgeSchedule.class));
    runner
        .withPropertyValues("app.auth.purge.enabled=true")
        .run(
            context ->
                await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> verify(purge, atLeastOnce()).purgeExpired()));
  }
}
