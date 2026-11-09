package com.portfolio.wallet.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Startup checks of {@code app.database} (documentations/06 MVP-ADR-06; doc 07 D14; Q6). */
class DatabaseTimeoutPropertiesTest {

  private static DatabaseTimeoutProperties of(String lock, String statement) {
    return new DatabaseTimeoutProperties(
        lock == null ? null : Duration.parse(lock),
        statement == null ? null : Duration.parse(statement));
  }

  @Test
  void theMvpDefaultsAndEqualValuesAreAccepted() {
    DatabaseTimeoutProperties defaults = of("PT5S", "PT10S");
    assertThat(defaults.lockTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(defaults.statementTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(of("PT0.2S", "PT0.2S").statementTimeout()).isEqualTo(Duration.ofMillis(200));
    assertThat(of("PT0.001S", "PT0.001S").lockTimeout()).isEqualTo(Duration.ofMillis(1));
  }

  @Test
  void bothAreRequiredAndPositive() {
    assertThatThrownBy(() -> of(null, "PT10S"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("app.database.lock-timeout is required");
    assertThatThrownBy(() -> of("PT5S", null))
        .hasMessage("app.database.statement-timeout is required");
    assertThatThrownBy(() -> of("PT0S", "PT10S"))
        .hasMessage("app.database.lock-timeout must be positive");
    assertThatThrownBy(() -> of("PT-1S", "PT10S"))
        .hasMessage("app.database.lock-timeout must be positive");
    assertThatThrownBy(() -> of("PT5S", "PT0S"))
        .hasMessage("app.database.statement-timeout must be positive");
  }

  @Test
  void bothAreWholeMillisecondsWithinPostgresqlsLimit() {
    assertThatThrownBy(() -> of("PT0.0005S", "PT10S"))
        .hasMessage("app.database.lock-timeout must be whole milliseconds");
    assertThatThrownBy(() -> of("PT5S", "PT10.0000001S"))
        .hasMessage("app.database.statement-timeout must be whole milliseconds");
    Duration max = Duration.ofMillis(DatabaseTimeoutProperties.MAX_MILLIS);
    assertThat(new DatabaseTimeoutProperties(max, max).lockTimeout()).isEqualTo(max);
    assertThatThrownBy(
            () -> new DatabaseTimeoutProperties(Duration.ofSeconds(5), max.plusMillis(1)))
        .hasMessage("app.database.statement-timeout must be at most 2147483647 ms");
  }

  @Test
  void theStatementTimeoutIsNotShorterThanTheLockTimeout() {
    assertThatThrownBy(() -> of("PT5S", "PT4.999S"))
        .hasMessage(
            "app.database.statement-timeout must not be shorter than app.database.lock-timeout");
  }

  @Test
  void theApplicationBindsTheConfiguredValuesAndRefusesToStartOnABadOne() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(DatabaseTimeoutConfiguration.class);

    runner
        .withPropertyValues("app.database.lock-timeout=5s", "app.database.statement-timeout=10s")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              DatabaseTimeoutProperties properties =
                  context.getBean(DatabaseTimeoutProperties.class);
              assertThat(properties.lockTimeout()).isEqualTo(Duration.ofSeconds(5));
              assertThat(properties.statementTimeout()).isEqualTo(Duration.ofSeconds(10));
            });
    runner
        .withPropertyValues("app.database.lock-timeout=5s", "app.database.statement-timeout=1s")
        .run(context -> assertThat(context).hasFailed());
    runner.run(context -> assertThat(context).hasFailed());
  }

  /** The owner's initial MVP defaults (2026-11-06, Q6) are what application.yaml ships. */
  @Test
  void theShippedConfigurationHoldsTheMvpDefaults() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabaseTimeoutConfiguration.class)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              DatabaseTimeoutProperties properties =
                  context.getBean(DatabaseTimeoutProperties.class);
              assertThat(properties.lockTimeout()).isEqualTo(Duration.ofSeconds(5));
              assertThat(properties.statementTimeout()).isEqualTo(Duration.ofSeconds(10));
            });
  }
}
