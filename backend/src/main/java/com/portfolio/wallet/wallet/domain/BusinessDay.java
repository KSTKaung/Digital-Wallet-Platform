package com.portfolio.wallet.wallet.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The business calendar day used for daily limits (documentations/06 MVP-ADR-22). Timestamps are
 * UTC instants; the day is the calendar date in the configured zone (Asia/Bangkok in the MVP), so
 * it resets at midnight in that zone. The zone is passed in, never hard-coded.
 */
public final class BusinessDay {

  private final ZoneId zone;

  public BusinessDay(ZoneId zone) {
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  public ZoneId zone() {
    return zone;
  }

  /** The business day an instant falls on. */
  public LocalDate dateOf(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return LocalDate.ofInstant(instant, zone);
  }

  /** The current business day according to the (injectable) clock. */
  public LocalDate today(Clock clock) {
    Objects.requireNonNull(clock, "clock");
    return dateOf(clock.instant());
  }
}
