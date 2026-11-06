package com.portfolio.wallet.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

/**
 * The shared purge (documentations/07 D04; doc 06 MVP-ADR-06 "Clean-up jobs"): batches until one
 * comes back short, one cut-off per run, and a failure is logged by table and exception class only.
 */
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyPurgeTest {

  private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
  private static final int BATCH = 3;
  private static final String DETAIL = "key (customer_id)=(7c1e-secret) violates";

  private final IdempotencyStore store = mock(IdempotencyStore.class);
  private final IdempotencyPurge purge =
      new IdempotencyPurge(
          store, "transfer.idempotency_records", BATCH, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void deletesBatchesUntilOneComesBackShortWithOneCutOff(CapturedOutput output) {
    when(store.deleteExpired(NOW, BATCH)).thenReturn(BATCH, BATCH, 1);

    assertThat(purge.purgeExpired()).isEqualTo(7);
    verify(store, times(3)).deleteExpired(NOW, BATCH);
    assertThat(output.getAll()).contains("table=transfer.idempotency_records").contains("count=7");
  }

  @Test
  void nothingExpiredIsOneStatement() {
    when(store.deleteExpired(NOW, BATCH)).thenReturn(0);

    assertThat(purge.purgeExpired()).isZero();
    verify(store, times(1)).deleteExpired(any(), anyInt());
  }

  @Test
  void aFailureEndsTheRunWithoutThrowing() {
    when(store.deleteExpired(NOW, BATCH))
        .thenReturn(BATCH)
        .thenThrow(new QueryTimeoutException("timeout"));

    assertThat(purge.purgeExpired()).isEqualTo(BATCH);
  }

  @Test
  void aFailureIsLoggedByClassOnlyAndTheNextRunStartsAgain(CapturedOutput output) {
    when(store.deleteExpired(any(), anyInt()))
        .thenThrow(new DataIntegrityViolationException(DETAIL))
        .thenReturn(2);

    assertThatCode(() -> assertThat(purge.purgeExpired()).isZero()).doesNotThrowAnyException();
    assertThat(output.getAll())
        .contains("table=transfer.idempotency_records")
        .contains("DataIntegrityViolationException")
        .doesNotContain("7c1e-secret");

    assertThat(purge.purgeExpired()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "idempotency_records", "wallet.", "Wallet.records", "a.b;drop"})
  void refusesATableNameThatIsNotSchemaQualified(String table) {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    assertThatThrownBy(() -> new IdempotencyPurge(store, table, 1, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAnInvalidConfiguration() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    assertThatThrownBy(() -> new IdempotencyPurge(store, "wallet.idempotency_records", 0, clock))
        .hasMessageContaining("batch size");
    assertThatThrownBy(() -> new IdempotencyPurge(null, "wallet.idempotency_records", 1, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotencyPurge(store, null, 1, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotencyPurge(store, "wallet.idempotency_records", 1, null))
        .isInstanceOf(NullPointerException.class);
  }
}
