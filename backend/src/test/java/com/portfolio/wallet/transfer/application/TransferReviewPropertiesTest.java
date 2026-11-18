package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link TransferReviewProperties}: checked at startup (documentations/06 MVP-ADR-24). */
class TransferReviewPropertiesTest {

  private static final Duration DAY = Duration.ofHours(24);
  private static final Duration HALF_DAY = Duration.ofHours(12);

  @Test
  void theMvpDurationIs24HoursAndTheReminder12HoursInSeconds() {
    TransferReviewProperties mvp = new TransferReviewProperties(DAY, HALF_DAY);
    assertThat(mvp.durationSeconds()).isEqualTo(86_400);
    assertThat(mvp.reminderAfterSeconds()).isEqualTo(43_200);
    TransferReviewProperties smallest =
        new TransferReviewProperties(Duration.ofSeconds(2), Duration.ofSeconds(1));
    assertThat(smallest.durationSeconds()).isEqualTo(2);
    assertThat(smallest.reminderAfterSeconds()).isEqualTo(1);
  }

  @Test
  void aMissingShortNegativeOrFractionalDurationFailsTheStart() {
    for (Duration bad :
        new Duration[] {
          null,
          Duration.ZERO,
          Duration.ofSeconds(-1),
          Duration.ofMillis(1500),
          Duration.ofMillis(999)
        }) {
      assertThatThrownBy(() -> new TransferReviewProperties(bad, Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("app.transfer.review.duration");
    }
  }

  /** The reminder must come before the deadline: shorter than the duration, never equal. */
  @Test
  void aReminderThatIsMissingFractionalOrNotBeforeTheDeadlineFailsTheStart() {
    for (Duration bad :
        new Duration[] {
          null,
          Duration.ZERO,
          Duration.ofSeconds(-1),
          Duration.ofMillis(999),
          Duration.ofMillis(43_200_500),
          DAY,
          DAY.plusSeconds(1)
        }) {
      assertThatThrownBy(() -> new TransferReviewProperties(DAY, bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("app.transfer.review.reminder-after");
    }
    assertThat(new TransferReviewProperties(DAY, DAY.minusSeconds(1)).reminderAfterSeconds())
        .isEqualTo(86_399);
  }
}
