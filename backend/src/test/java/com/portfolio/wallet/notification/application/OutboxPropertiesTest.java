package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The outbox's retry policy is checked at startup (documentations/06 MVP-ADR-25). */
class OutboxPropertiesTest {

  private static final List<Duration> MVP_BACKOFF =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(15),
          Duration.ofHours(1));
  private static final Duration POLL = Duration.ofSeconds(10);
  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final Duration MARGIN = Duration.ofSeconds(30);
  private static final Duration LOOKUP = Duration.ofSeconds(5);

  @Test
  void theMvpValuesAreAcceptedAndGiveTheWaitAfterEachAttempt() {
    OutboxProperties properties =
        new OutboxProperties(5, MVP_BACKOFF, 50, POLL, LEASE, MARGIN, LOOKUP);

    assertThat(properties.waitAfter(1)).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.waitAfter(4)).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void theBackoffMustHaveOneEntryFewerThanTheAttempts() {
    assertThatThrownBy(() -> new OutboxProperties(4, MVP_BACKOFF, 50, POLL, LEASE, MARGIN, LOOKUP))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("backoff");
    assertThatThrownBy(() -> new OutboxProperties(5, null, 50, POLL, LEASE, MARGIN, LOOKUP))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyValueMustBePositive() {
    assertThatThrownBy(() -> new OutboxProperties(0, List.of(), 50, POLL, LEASE, MARGIN, LOOKUP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new OutboxProperties(2, List.of(Duration.ZERO), 50, POLL, LEASE, MARGIN, LOOKUP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(5, MVP_BACKOFF, 0, POLL, LEASE, MARGIN, LOOKUP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new OutboxProperties(5, MVP_BACKOFF, 50, Duration.ZERO, LEASE, MARGIN, LOOKUP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(5, MVP_BACKOFF, 50, null, LEASE, MARGIN, LOOKUP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new OutboxProperties(5, MVP_BACKOFF, 50, POLL, Duration.ZERO, MARGIN, LOOKUP))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lease");
    assertThatThrownBy(
            () ->
                new OutboxProperties(
                    5, MVP_BACKOFF, 50, POLL, LEASE, Duration.ofSeconds(-1), LOOKUP))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lease-safety-margin");
    // S1: JDBC query timeouts are whole seconds.
    assertThatThrownBy(
            () ->
                new OutboxProperties(
                    5, MVP_BACKOFF, 50, POLL, LEASE, MARGIN, Duration.ofMillis(500)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("address-lookup-timeout");
    assertThatThrownBy(() -> new OutboxProperties(5, MVP_BACKOFF, 50, POLL, LEASE, MARGIN, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new OutboxProperties(1, List.of(), 1, POLL, LEASE, MARGIN, LOOKUP).maxAttempts())
        .isEqualTo(1);
  }
}
