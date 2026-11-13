package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.transfer.application.HeldTransferDecider.Request;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRepository.DueTransfer;
import com.portfolio.wallet.transfer.persistence.TransferRepository.LockMode;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The expiry job's run (documentations/06 MVP-ADR-24 "Expiry", "Concurrency", case 5; doc 08 §4C.4
 * criteria 8, 12): one transaction per transfer with the D14 timeouts first, the row locked with
 * {@code SKIP LOCKED}, a transfer that is gone or no longer held skipped silently, a failure logged
 * by id and class and the run going on, pages read after the last candidate, and the per-run
 * maximum.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferExpiryServiceTest {

  private static final Instant DEADLINE = Instant.parse("2026-11-12T05:00:00Z");

  private final TransferRepository transfers = mock(TransferRepository.class);
  private final HeldTransferDecider decider = mock(HeldTransferDecider.class);
  private final TransactionTimeouts timeouts = mock(TransactionTimeouts.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  private TransferExpiryService service(int maxPerRun) {
    return new TransferExpiryService(
        transfers,
        decider,
        timeouts,
        new TransferExpiryProperties(false, Duration.ofMinutes(1), maxPerRun),
        transactions);
  }

  private static DueTransfer due(int second) {
    return new DueTransfer(UUID.randomUUID(), DEADLINE.plusSeconds(second));
  }

  private static TransferRow row(UUID id, String status) {
    return new TransferRow(
        id,
        "TRF-1",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new BigDecimal("1300.00"),
        "THB",
        "rent",
        status,
        UUID.randomUUID(),
        LocalDate.parse("2026-11-11"),
        UUID.randomUUID(),
        DEADLINE.minus(Duration.ofHours(24)),
        DEADLINE,
        null,
        null,
        null,
        null,
        1,
        DEADLINE,
        DEADLINE);
  }

  private void held(DueTransfer... candidates) {
    for (DueTransfer candidate : candidates) {
      when(transfers.lockForUpdate(candidate.id(), LockMode.SKIP_LOCKED))
          .thenReturn(Optional.of(row(candidate.id(), "PENDING_REVIEW")));
    }
  }

  @BeforeEach
  void setUp() {
    // The mock manager marks the transaction active while it runs, as a real one does.
    when(transactions.getTransaction(any()))
        .thenAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return new SimpleTransactionStatus();
            });
    doAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactions)
        .commit(any());
    doAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactions)
        .rollback(any());
  }

  @AfterEach
  void endTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void eachDueTransferIsExpiredInATransactionOfItsOwnAfterTheTimeouts() {
    DueTransfer first = due(1);
    DueTransfer second = due(2);
    when(transfers.findDueForExpiry(null, 500)).thenReturn(List.of(first, second));
    when(transfers.findDueForExpiry(second, 498)).thenReturn(List.of());
    held(first, second);

    assertThat(service(500).expireDue()).isEqualTo(2);

    InOrder order = inOrder(timeouts, transfers, decider, transactions);
    // The candidate query, bounded by the timeouts too.
    order.verify(timeouts).apply();
    order.verify(transfers).findDueForExpiry(null, 500);
    order.verify(transactions).commit(any());
    for (DueTransfer candidate : List.of(first, second)) {
      order.verify(transactions).getTransaction(any());
      order.verify(timeouts).apply();
      order.verify(transfers).lockForUpdate(candidate.id(), LockMode.SKIP_LOCKED);
      ArgumentCaptor<TransferRow> locked = ArgumentCaptor.forClass(TransferRow.class);
      order.verify(decider).decide(locked.capture(), eq(Request.EXPIRY_JOB), isNull(), isNull());
      assertThat(locked.getValue().id()).isEqualTo(candidate.id());
      order.verify(transactions).commit(any());
    }
    order.verify(transfers).findDueForExpiry(second, 498);
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions, times(4)).getTransaction(definition.capture());
    assertThat(definition.getAllValues())
        .allSatisfy(
            d ->
                assertThat(d.getIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED));
  }

  @Test
  void aRowAnotherTransactionHoldsOrThatIsNoLongerHeldIsSkippedSilently() {
    DueTransfer lockedElsewhere = due(1);
    DueTransfer cancelled = due(2);
    when(transfers.findDueForExpiry(null, 500)).thenReturn(List.of(lockedElsewhere, cancelled));
    when(transfers.lockForUpdate(lockedElsewhere.id(), LockMode.SKIP_LOCKED))
        .thenReturn(Optional.empty());
    when(transfers.lockForUpdate(cancelled.id(), LockMode.SKIP_LOCKED))
        .thenReturn(Optional.of(row(cancelled.id(), "CANCELLED")));

    assertThat(service(500).expireDue()).isZero();

    verify(decider, never()).decide(any(), any(), any(), any());
    verify(transactions, never()).rollback(any());
  }

  @Test
  void aFailingTransferRollsBackAloneIsLoggedByIdAndClassAndTheRunGoesOn(CapturedOutput output) {
    DueTransfer failing = due(1);
    DueTransfer next = due(2);
    when(transfers.findDueForExpiry(null, 500)).thenReturn(List.of(failing, next));
    when(transfers.findDueForExpiry(next, 499)).thenReturn(List.of());
    held(failing, next);
    doThrow(new IllegalStateException("secret detail 4f2a"))
        .when(decider)
        .decide(
            org.mockito.ArgumentMatchers.argThat(r -> r != null && r.id().equals(failing.id())),
            any(),
            any(),
            any());

    assertThat(service(500).expireDue()).isEqualTo(1);

    verify(transactions, times(1)).rollback(any());
    assertThat(output.getAll())
        .contains("transferId=" + failing.id())
        .contains("failure=IllegalStateException")
        .doesNotContain("secret detail 4f2a")
        .doesNotContain("rent");
  }

  /**
   * Pages start after the last candidate, so a transfer that stays due (skipped or failed) is not
   * handled twice in one run; the run stops at the per-run maximum.
   */
  @Test
  void pagesFollowTheLastCandidateAndTheRunStopsAtItsMaximum() {
    DueTransfer a = due(1);
    DueTransfer b = due(2);
    DueTransfer c = due(3);
    when(transfers.findDueForExpiry(null, 3)).thenReturn(List.of(a, b));
    when(transfers.findDueForExpiry(b, 1)).thenReturn(List.of(c));
    when(transfers.lockForUpdate(any(), eq(LockMode.SKIP_LOCKED))).thenReturn(Optional.empty());

    assertThat(service(3).expireDue()).isZero();

    verify(transfers).findDueForExpiry(null, 3);
    verify(transfers).findDueForExpiry(b, 1);
    // The maximum is reached: no further page is read in this run.
    verify(transfers, times(2)).findDueForExpiry(any(), anyInt());
    verify(transfers, times(3)).lockForUpdate(any(), any());
  }

  /**
   * Security L1: failures do not use up the per-run maximum, so transfers that fail on every run
   * cannot keep a later due transfer from expiring. Each failure is logged under the alert key.
   */
  @Test
  void failuresDoNotUseUpTheMaximumSoALaterDueTransferStillExpires(CapturedOutput output) {
    DueTransfer first = due(1);
    DueTransfer second = due(2);
    DueTransfer third = due(3);
    DueTransfer good = due(4);
    when(transfers.findDueForExpiry(null, 2)).thenReturn(List.of(first, second));
    when(transfers.findDueForExpiry(second, 2)).thenReturn(List.of(third, good));
    when(transfers.findDueForExpiry(good, 1)).thenReturn(List.of());
    held(first, second, third, good);
    List<UUID> failing = List.of(first.id(), second.id(), third.id());
    doThrow(new IllegalStateException("broken row"))
        .when(decider)
        .decide(
            org.mockito.ArgumentMatchers.argThat(r -> r != null && failing.contains(r.id())),
            any(),
            any(),
            any());

    // Three failing candidates, more than the maximum of 2, then a good one: it still expires.
    assertThat(service(2).expireDue()).isEqualTo(1);

    ArgumentCaptor<TransferRow> decided = ArgumentCaptor.forClass(TransferRow.class);
    verify(decider, times(4)).decide(decided.capture(), any(), any(), any());
    assertThat(decided.getAllValues().getLast().id()).isEqualTo(good.id());
    verify(transactions, times(3)).rollback(any());
    verify(transfers).findDueForExpiry(good, 1);
    for (UUID id : failing) {
      assertThat(output.getAll())
          .contains("TRANSFER_EXPIRY_FAILED")
          .contains("transferId=" + id + ", failure=IllegalStateException");
    }
    assertThat(output.getAll()).doesNotContain("broken row").contains("limitReached=false");
  }

  @Test
  void aFailingCandidateQueryEndsTheRun(CapturedOutput output) {
    when(transfers.findDueForExpiry(null, 500))
        .thenThrow(new DataAccessResourceFailureException("db down 77e1"));

    assertThat(service(500).expireDue()).isZero();

    verify(transfers, never()).lockForUpdate(any(), any());
    assertThat(output.getAll())
        .contains("Transfer expiry run stopped")
        .contains("failure=DataAccessResourceFailureException")
        .doesNotContain("db down 77e1");
  }

  @Test
  void anEmptyFirstPageEndsTheRunQuietly() {
    when(transfers.findDueForExpiry(null, 500)).thenReturn(new ArrayList<>());

    assertThat(service(500).expireDue()).isZero();

    verify(transfers, never()).lockForUpdate(any(), any());
  }

  @Test
  void aRunInsideACallersTransactionIsRefused() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> service(500).expireDue())
        .isInstanceOf(IllegalTransactionStateException.class);

    verify(transactions, never()).getTransaction(any());
    verify(transfers, never()).findDueForExpiry(any(), anyInt());
  }
}
