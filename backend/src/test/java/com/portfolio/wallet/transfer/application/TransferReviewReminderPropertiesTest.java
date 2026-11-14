package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code app.transfer.review-reminder}, checked at startup, and its schedule. */
class TransferReviewReminderPropertiesTest {

  @Test
  void theMvpValuesAreAccepted() {
    TransferReviewReminderProperties properties =
        new TransferReviewReminderProperties(true, Duration.ofMinutes(1), 500);

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.interval()).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.maxPerRun()).isEqualTo(500);
  }

  @Test
  void aMissingOrNonPositiveIntervalIsRefused() {
    for (Duration interval : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThatThrownBy(() -> new TransferReviewReminderProperties(true, interval, 500))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("app.transfer.review-reminder.interval");
    }
  }

  @Test
  void aMaximumBelowOneIsRefused() {
    assertThatThrownBy(() -> new TransferReviewReminderProperties(true, Duration.ofMinutes(1), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.transfer.review-reminder.max-per-run");
  }

  @Test
  void eachScheduledRunRemindsTheDueTransfers() {
    TransferReviewReminderService reminders = mock(TransferReviewReminderService.class);

    new TransferReviewReminderSchedule(reminders).run();

    verify(reminders).remindDue();
  }
}
