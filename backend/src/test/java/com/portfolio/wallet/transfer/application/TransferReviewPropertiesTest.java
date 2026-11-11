package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link TransferReviewProperties}: checked at startup (documentations/06 MVP-ADR-24). */
class TransferReviewPropertiesTest {

  @Test
  void theMvpDurationIs24HoursInSeconds() {
    assertThat(new TransferReviewProperties(Duration.ofHours(24)).durationSeconds())
        .isEqualTo(86_400);
    assertThat(new TransferReviewProperties(Duration.ofSeconds(1)).durationSeconds()).isEqualTo(1);
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
      assertThatThrownBy(() -> new TransferReviewProperties(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("app.transfer.review.duration");
    }
  }
}
