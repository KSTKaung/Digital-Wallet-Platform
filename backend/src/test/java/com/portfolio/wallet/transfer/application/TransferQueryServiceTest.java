package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.transfer.domain.RejectionCategory;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import com.portfolio.wallet.wallet.application.TransferCounterparties;
import com.portfolio.wallet.wallet.application.WalletLookupView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@link TransferQueryService} and {@link TransferViews} (documentations/06 MVP-ADR-24 "Visibility
 * (Q8)"; doc 08 §4C.2 Q8, Q10; §4C.4 criteria 13, 20).
 */
class TransferQueryServiceTest {

  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID RECEIVER = UUID.randomUUID();
  private static final UUID SENDER_WALLET = UUID.randomUUID();
  private static final UUID RECEIVER_WALLET = UUID.randomUUID();
  private static final Instant AT = Instant.parse("2026-11-10T05:00:00Z");

  private final TransferRepository transfers = mock(TransferRepository.class);
  private final TransferCounterparties counterparties = mock(TransferCounterparties.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final TransferQueryService service =
      new TransferQueryService(transfers, counterparties, transactions);

  {
    when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
  }

  static TransferRow row(UUID id, String status, String category) {
    boolean held = !List.of("COMPLETED", "BLOCKED").contains(status);
    return new TransferRow(
        id,
        "TRF-" + id,
        SENDER,
        RECEIVER,
        SENDER_WALLET,
        RECEIVER_WALLET,
        new BigDecimal("12.5"),
        "THB",
        "rent",
        status,
        UUID.randomUUID(),
        LocalDate.parse("2026-11-10"),
        UUID.randomUUID(),
        held ? AT : null,
        held ? AT.plus(Duration.ofHours(24)) : null,
        held && !"PENDING_REVIEW".equals(status) ? AT.plusSeconds(60) : null,
        category,
        "COMPLETED".equals(status) ? "POSTED" : null,
        null,
        0,
        AT,
        AT);
  }

  private void parties() {
    when(counterparties.of(any()))
        .thenReturn(
            Map.of(
                RECEIVER_WALLET, new WalletLookupView("Receiver R.", "*******2222"),
                SENDER_WALLET, new WalletLookupView("Sender S.", "*******1111")));
  }

  @Test
  void theSenderSeesTheReceiverAndTheReceiverSeesTheSender() {
    parties();
    UUID id = UUID.randomUUID();
    when(transfers.findVisible(any(), any())).thenReturn(Optional.of(row(id, "COMPLETED", null)));

    TransferView sent = service.get(SENDER, id);
    TransferView received = service.get(RECEIVER, id);

    assertThat(sent.direction()).isEqualTo("SENT");
    assertThat(sent.counterparty().maskedName()).isEqualTo("Receiver R.");
    assertThat(received.direction()).isEqualTo("RECEIVED");
    assertThat(received.counterparty().maskedName()).isEqualTo("Sender S.");
    assertThat(sent.amount()).isEqualByComparingTo("12.50");
    assertThat(sent.amount().scale()).isEqualTo(2);
    assertThat(sent.ledgerStatus()).isEqualTo("POSTED");
    assertThat(sent.toString()).doesNotContain("rent");
  }

  @Test
  void aRowTheRuleWouldHideCannotBeViewed() {
    UUID id = UUID.randomUUID();
    CounterpartyView party = new CounterpartyView("a", "b");
    for (TransferStatus status : TransferStatus.values()) {
      if (status != TransferStatus.COMPLETED) {
        TransferRow row = row(id, status.name(), status == TransferStatus.REJECTED ? "X" : null);
        assertThatThrownBy(() -> TransferViews.view(row, RECEIVER, party))
            .isInstanceOf(IllegalStateException.class);
      }
    }
    assertThatThrownBy(
            () -> TransferViews.view(row(id, "COMPLETED", null), UUID.randomUUID(), party))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @EnumSource(RejectionCategory.class)
  void aRejectionShowsOnlyTheCustomerCategory(RejectionCategory category) {
    TransferRow row = row(UUID.randomUUID(), "REJECTED", category.name());

    TransferView view = TransferViews.view(row, SENDER, new CounterpartyView("a", "b"));

    assertThat(view.rejectionReason()).isEqualTo(category.name());
    assertThat(view.reviewExpiresAt()).isNull();
    assertThat(TransferViews.toDomain(row).rejectionReasonCategory()).contains(category);
  }

  @Test
  void onlyAHeldTransferShowsItsDeadline() {
    TransferView held =
        TransferViews.view(
            row(UUID.randomUUID(), "PENDING_REVIEW", null), SENDER, new CounterpartyView("a", "b"));
    assertThat(held.reviewExpiresAt()).isEqualTo(AT.plus(Duration.ofHours(24)));
    assertThat(held.ledgerStatus()).isNull();
  }

  @Test
  void anInvisibleTransferIsTheIdentical404OnDetailAndStatus() {
    when(transfers.findVisible(any(), any())).thenReturn(Optional.empty());
    UUID id = UUID.randomUUID();

    for (Runnable call :
        List.<Runnable>of(() -> service.get(SENDER, id), () -> service.status(SENDER, id))) {
      assertThatThrownBy(call::run)
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(e.getMessage()).isEqualTo(ApiException.notFound().getMessage());
              });
    }
  }

  @Test
  void theStatusIsStatusAndLedgerStatusOnly() {
    UUID id = UUID.randomUUID();
    when(transfers.findVisible(SENDER, id)).thenReturn(Optional.of(row(id, "BLOCKED", null)));

    assertThat(service.status(SENDER, id)).isEqualTo(new TransferStatusView("BLOCKED", null));
    verifyNoInteractions(counterparties);
  }

  @Test
  void aPageCountsAndOffsetsAndLooksUpEachCounterpartyOnce() {
    parties();
    when(transfers.countVisible(SENDER)).thenReturn(41L);
    when(transfers.pageVisible(SENDER, 20, 40L))
        .thenReturn(List.of(row(UUID.randomUUID(), "COMPLETED", null)));

    TransferPage page = service.list(SENDER, 2, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.totalElements()).isEqualTo(41);
    assertThat(page.totalPages()).isEqualTo(3);
    assertThat(page.page()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(20);

    when(transfers.countVisible(RECEIVER)).thenReturn(0L);
    when(transfers.pageVisible(RECEIVER, 100, 0L)).thenReturn(List.of());
    assertThat(service.list(RECEIVER, 0, 100).totalPages()).isZero();
  }

  @Test
  void pageAndSizeAreBounded() {
    for (int[] bad : new int[][] {{-1, 20}, {0, 0}, {0, 101}}) {
      assertThatThrownBy(() -> service.list(SENDER, bad[0], bad[1]))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
    verifyNoInteractions(transfers);
  }

  @Test
  void viewsAndRowsPrintNoDescription() {
    TransferRow row = row(UUID.randomUUID(), "COMPLETED", null);
    assertThat(row.toString()).doesNotContain("rent").doesNotContain("12.5");
    assertThat(new CounterpartyView("Somchai J.", "*******1").toString()).doesNotContain("Somchai");
  }

  /** M3: the count and the page are read in one read-only REPEATABLE READ snapshot. */
  @Test
  void theCountAndThePageShareOneSnapshot() {
    when(transfers.countVisible(SENDER)).thenReturn(0L);
    when(transfers.pageVisible(SENDER, 20, 0L)).thenReturn(List.of());

    service.list(SENDER, 0, 20);

    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    org.mockito.Mockito.verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().isReadOnly()).isTrue();
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    org.mockito.Mockito.verify(transactions).commit(any());
  }
}
