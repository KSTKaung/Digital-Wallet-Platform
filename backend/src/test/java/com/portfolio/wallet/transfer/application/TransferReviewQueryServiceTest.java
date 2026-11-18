package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** The staff review reads of the transfer module (doc 06 MVP-ADR-24 amendment Q12). */
class TransferReviewQueryServiceTest {

  private static final Instant HELD_AT = Instant.parse("2026-11-10T05:00:00Z");

  private final TransferRepository transfers = mock(TransferRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final TransferReviewQueryService service =
      new TransferReviewQueryService(transfers, transactions);

  private static TransferRow row(UUID id, Instant heldAt) {
    return new TransferRow(
        id,
        "TRF-" + id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new BigDecimal("1300.5"),
        "THB",
        "a private note",
        "PENDING_REVIEW",
        UUID.randomUUID(),
        LocalDate.parse("2026-11-10"),
        UUID.randomUUID(),
        heldAt,
        heldAt == null ? null : heldAt.plusSeconds(86_400),
        null,
        null,
        null,
        null,
        0,
        HELD_AT,
        HELD_AT);
  }

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  }

  @Test
  void theQueueReadsCountAndPageInOneReadOnlySnapshot() {
    UUID first = UUID.randomUUID();
    when(transfers.countAwaitingReview()).thenReturn(41L);
    when(transfers.pageAwaitingReview(20, 40)).thenReturn(List.of(row(first, HELD_AT)));

    HeldTransferPage page = service.queue(2, 20);

    assertThat(page.items()).extracting(HeldTransferView::id).containsExactly(first);
    assertThat(page.items().getFirst().amount()).isEqualByComparingTo("1300.50");
    assertThat(page.items().getFirst().amount().scale()).isEqualTo(2);
    assertThat(page.totalElements()).isEqualTo(41);
    assertThat(page.totalPages()).isEqualTo(3);
    assertThat(page.page()).isEqualTo(2);
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().isReadOnly()).isTrue();
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @Test
  void pageAndSizeOutOfRangeAreValidationErrors() {
    for (int[] bounds : new int[][] {{-1, 20}, {0, 0}, {0, 101}}) {
      assertThatThrownBy(() -> service.queue(bounds[0], bounds[1]))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
    verify(transfers, never()).pageAwaitingReview(anyInt(), anyLong());
    assertThat(service.queue(0, 100).items()).isEmpty();
  }

  @Test
  void theDetailIsAnyTransferHeldForReviewAndOtherwiseTheIdentical404() {
    UUID id = UUID.randomUUID();
    when(transfers.findHeldForReview(id)).thenReturn(Optional.of(row(id, HELD_AT)));
    HeldTransferView view = service.get(id);
    assertThat(view.id()).isEqualTo(id);
    assertThat(view.toString()).doesNotContain("private note", "1300");

    UUID missing = UUID.randomUUID();
    when(transfers.findHeldForReview(missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(missing))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getMessage()).isEqualTo(ApiException.notFound().getMessage()));
  }

  @Test
  void aRowNeverHeldHasNoStaffView() {
    assertThatThrownBy(() -> TransferReviewQueryService.view(row(UUID.randomUUID(), null)))
        .isInstanceOf(IllegalStateException.class);
  }
}
