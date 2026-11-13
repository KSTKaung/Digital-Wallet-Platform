package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code app.transfer.expiry}, checked at startup, and its schedule. */
class TransferExpiryPropertiesTest {

  @Test
  void theMvpValuesAreAccepted() {
    TransferExpiryProperties properties =
        new TransferExpiryProperties(true, Duration.ofMinutes(1), 500);

    assertThat(properties.interval()).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.maxPerRun()).isEqualTo(500);
  }

  @Test
  void aMissingOrNonPositiveIntervalIsRefused() {
    for (Duration interval : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThatThrownBy(() -> new TransferExpiryProperties(true, interval, 500))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("app.transfer.expiry.interval");
    }
  }

  @Test
  void aMaximumBelowOneIsRefused() {
    assertThatThrownBy(() -> new TransferExpiryProperties(true, Duration.ofMinutes(1), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.transfer.expiry.max-per-run");
  }

  @Test
  void eachScheduledRunExpiresTheDueTransfers() {
    TransferExpiryService expiry = mock(TransferExpiryService.class);

    new TransferExpirySchedule(expiry).run();

    verify(expiry).expireDue();
  }
}
