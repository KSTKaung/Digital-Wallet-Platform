package com.portfolio.wallet.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** documentations/06 MVP-ADR-22; doc 08 §4.5 test case 10 (Bangkok day boundary). */
class BusinessDayTest {

  /** The documented business zone, passed in as the configuration would. */
  private final BusinessDay bangkok = new BusinessDay(ZoneId.of("Asia/Bangkok"));

  @Test
  void midnightBangkokIsSeventeenHundredUtc() {
    assertThat(bangkok.dateOf(Instant.parse("2026-10-30T16:59:59Z")))
        .isEqualTo(LocalDate.of(2026, 10, 30));
    assertThat(bangkok.dateOf(Instant.parse("2026-10-30T17:00:00Z")))
        .isEqualTo(LocalDate.of(2026, 10, 31));
  }

  @Test
  void readsTheDayFromAFixedClock() {
    Clock beforeMidnight = Clock.fixed(Instant.parse("2026-10-30T16:59:59Z"), ZoneOffset.UTC);
    Clock atMidnight = Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"), ZoneOffset.UTC);

    assertThat(bangkok.today(beforeMidnight)).isNotEqualTo(bangkok.today(atMidnight));
    assertThat(bangkok.today(atMidnight)).isEqualTo(LocalDate.of(2026, 10, 31));
  }

  @Test
  void theZoneIsAParameter() {
    BusinessDay utc = new BusinessDay(ZoneOffset.UTC);

    assertThat(utc.dateOf(Instant.parse("2026-10-30T17:00:00Z")))
        .isEqualTo(LocalDate.of(2026, 10, 30));
    assertThat(bangkok.zone()).isEqualTo(ZoneId.of("Asia/Bangkok"));
  }
}
