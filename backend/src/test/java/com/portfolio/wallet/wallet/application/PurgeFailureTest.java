package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import com.portfolio.wallet.wallet.persistence.LookupAttemptRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Purge failures (documentations/07 D04; MVP-ADR-20; the purge services' contract): a failing run
 * never throws into the scheduler, logs the table and the exception class only, never the
 * exception's message (which can hold SQL and row values), and the next run starts again. The
 * scheduled jobs only delegate.
 */
@ExtendWith(OutputCaptureExtension.class)
class PurgeFailureTest {

  private static final String DETAIL = "key (customer_id)=(7c1e-secret) violates";

  private final IdempotencyRecordRepository records = mock(IdempotencyRecordRepository.class);
  private final LookupAttemptRepository attempts = mock(LookupAttemptRepository.class);

  private final IdempotencyPurgeService idempotencyPurge =
      new IdempotencyPurgeService(
          records,
          new IdempotencyProperties(
              Duration.ofHours(24), new IdempotencyProperties.Purge(true, Duration.ofHours(1), 2)),
          Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"), ZoneOffset.UTC));

  private final LookupPurgeService lookupPurge =
      new LookupPurgeService(
          attempts,
          new LookupProperties(
              10,
              30,
              Duration.ofMinutes(1),
              new LookupProperties.Purge(true, Duration.ofHours(1), 2)));

  @Test
  void anIdempotencyPurgeFailureIsLoggedByClassOnlyAndTheNextRunStartsAgain(CapturedOutput output) {
    when(records.deleteExpired(any(), anyInt()))
        .thenThrow(new DataIntegrityViolationException(DETAIL))
        .thenReturn(2, 1);

    assertThatCode(() -> assertThat(idempotencyPurge.purgeExpired()).isZero())
        .doesNotThrowAnyException();
    assertThat(output.getAll())
        .contains("table=wallet.idempotency_records")
        .contains("DataIntegrityViolationException")
        .doesNotContain("7c1e-secret");

    assertThat(idempotencyPurge.purgeExpired()).isEqualTo(3);
    verify(records, times(3)).deleteExpired(any(), anyInt());
  }

  @Test
  void aLookupPurgeFailureIsLoggedByClassOnlyAndTheNextRunStartsAgain(CapturedOutput output) {
    when(attempts.deleteOlderThan(anyLong(), anyInt()))
        .thenThrow(new DataIntegrityViolationException(DETAIL))
        .thenReturn(2, 0);

    assertThatCode(() -> assertThat(lookupPurge.purgeExpired()).isZero())
        .doesNotThrowAnyException();
    assertThat(output.getAll())
        .contains("table=wallet.lookup_attempts")
        .contains("DataIntegrityViolationException")
        .doesNotContain("7c1e-secret");

    assertThat(lookupPurge.purgeExpired()).isEqualTo(2);
  }

  @Test
  void theScheduledJobsDelegateToTheirPurge() {
    IdempotencyPurgeService idempotency = mock(IdempotencyPurgeService.class);
    LookupPurgeService lookup = mock(LookupPurgeService.class);

    new IdempotencyPurgeSchedule(idempotency).run();
    new LookupPurgeSchedule(lookup).run();

    verify(idempotency).purgeExpired();
    verify(lookup).purgeExpired();
  }
}
