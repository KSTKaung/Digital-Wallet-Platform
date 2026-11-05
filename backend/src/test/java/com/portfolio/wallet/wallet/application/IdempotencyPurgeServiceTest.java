package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class IdempotencyPurgeServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
  private static final int BATCH = 3;

  private final IdempotencyRecordRepository records = mock(IdempotencyRecordRepository.class);
  private final IdempotencyPurgeService purge =
      new IdempotencyPurgeService(
          records,
          new IdempotencyProperties(
              Duration.ofHours(24),
              new IdempotencyProperties.Purge(true, Duration.ofHours(1), BATCH)),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void deletesBatchesUntilOneComesBackShortWithOneCutOff() {
    when(records.deleteExpired(NOW, BATCH)).thenReturn(BATCH, BATCH, 1);

    assertThat(purge.purgeExpired()).isEqualTo(7);
    verify(records, times(3)).deleteExpired(NOW, BATCH);
  }

  @Test
  void nothingExpiredIsOneStatement() {
    when(records.deleteExpired(NOW, BATCH)).thenReturn(0);

    assertThat(purge.purgeExpired()).isZero();
    verify(records, times(1)).deleteExpired(any(), anyInt());
  }

  @Test
  void aFailureEndsTheRunWithoutThrowing() {
    when(records.deleteExpired(NOW, BATCH))
        .thenReturn(BATCH)
        .thenThrow(new QueryTimeoutException("timeout"));

    assertThat(purge.purgeExpired()).isEqualTo(BATCH);
  }

  @Test
  void aTtlUnderOneSecondIsRefusedAtStartup() {
    IdempotencyProperties.Purge validPurge =
        new IdempotencyProperties.Purge(true, Duration.ofHours(1), 1);

    assertThat(new IdempotencyProperties(Duration.ofSeconds(1), validPurge).ttl())
        .isEqualTo(Duration.ofSeconds(1));
    for (Duration tooShort :
        new Duration[] {
          Duration.ofSeconds(1).minusNanos(1), Duration.ofMillis(999), Duration.ofNanos(1)
        }) {
      assertThatThrownBy(() -> new IdempotencyProperties(tooShort, validPurge))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least 1 second");
    }
  }

  @Test
  void propertiesAreCheckedAtStartup() {
    IdempotencyProperties.Purge validPurge =
        new IdempotencyProperties.Purge(true, Duration.ofHours(1), 1);

    assertThat(new IdempotencyProperties(Duration.ofDays(30), validPurge).ttl())
        .isEqualTo(Duration.ofDays(30));
    assertThatThrownBy(() -> new IdempotencyProperties(null, validPurge))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdempotencyProperties(Duration.ZERO, validPurge))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofHours(-1), validPurge))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new IdempotencyProperties(Duration.ofDays(30).plusSeconds(1), validPurge))
        .hasMessageContaining("ttl");
    assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofHours(24), null))
        .hasMessageContaining("purge");
    assertThatThrownBy(() -> new IdempotencyProperties.Purge(true, null, 1))
        .hasMessageContaining("interval");
    assertThatThrownBy(() -> new IdempotencyProperties.Purge(true, Duration.ZERO, 1))
        .hasMessageContaining("interval");
    assertThatThrownBy(() -> new IdempotencyProperties.Purge(true, Duration.ofSeconds(-1), 1))
        .hasMessageContaining("interval");
    assertThatThrownBy(() -> new IdempotencyProperties.Purge(true, Duration.ofHours(1), 0))
        .hasMessageContaining("batch-size");
  }
}
