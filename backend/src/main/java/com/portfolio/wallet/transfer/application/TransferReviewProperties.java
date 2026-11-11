package com.portfolio.wallet.transfer.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The manual review of a held transfer ({@code app.transfer.review}; documentations/06 MVP-ADR-24
 * "Expiry", owner 2026-10-30, doc 07 D28). Checked at startup.
 *
 * @param duration how long a transfer may stay {@code PENDING_REVIEW}, counted from {@code
 *     held_at}: 24 hours (MVP-ADR-24). Whole seconds and at least 1 second, so {@code
 *     review_expires_at} always lies after {@code held_at} (V4 {@code ck_transfers_review_window})
 */
@ConfigurationProperties(prefix = "app.transfer.review")
public record TransferReviewProperties(Duration duration) {

  private static final String PREFIX = "app.transfer.review.";

  public TransferReviewProperties {
    if (duration == null
        || duration.compareTo(Duration.ofSeconds(1)) < 0
        || duration.toNanosPart() != 0) {
      throw new IllegalArgumentException(PREFIX + "duration must be whole seconds, at least 1s");
    }
  }

  /** The duration in whole seconds, for the database's deadline. */
  public long durationSeconds() {
    return duration.toSeconds();
  }
}
