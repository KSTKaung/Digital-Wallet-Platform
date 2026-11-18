package com.portfolio.wallet.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.ledger.application.LedgerHistoryItem;
import com.portfolio.wallet.ledger.application.LedgerHistoryPage;
import com.portfolio.wallet.ledger.application.LedgerHistoryService;
import com.portfolio.wallet.wallet.application.WalletQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link TransactionHistoryService}: {@code GET /transactions} over the caller's own ledger account
 * (documentations/02 §1.5, §2.4; doc 06 MVP-ADR-11 amendment; doc 08 §4C.4 criterion 17).
 */
class TransactionHistoryServiceTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID WALLET = UUID.randomUUID();
  private static final Instant AT = Instant.parse("2026-11-14T03:00:00Z");

  private final WalletQueryService wallets = mock(WalletQueryService.class);
  private final LedgerHistoryService ledger = mock(LedgerHistoryService.class);
  private final TransactionHistoryService service = new TransactionHistoryService(wallets, ledger);

  @Test
  void theCallersLedgerItemsBecomePostedHistoryItems() {
    when(wallets.findWalletId(CALLER)).thenReturn(Optional.of(WALLET));
    LedgerHistoryItem in =
        new LedgerHistoryItem("TRF-1", "TRANSFER_IN", new BigDecimal("40.00"), "THB", AT);
    LedgerHistoryItem deposit =
        new LedgerHistoryItem("DEP-1", "DEPOSIT", new BigDecimal("1000.00"), "THB", AT);
    when(ledger.customerHistory(WALLET, 1, 2))
        .thenReturn(new LedgerHistoryPage(List.of(in, deposit), 1, 2, 5, 3));

    TransactionPage page = service.list(CALLER, 1, 2);

    assertThat(page.items())
        .containsExactly(
            new TransactionView(
                "TRF-1", "TRANSFER_IN", new BigDecimal("40.00"), "THB", AT, "POSTED"),
            new TransactionView(
                "DEP-1", "DEPOSIT", new BigDecimal("1000.00"), "THB", AT, "POSTED"));
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.totalElements()).isEqualTo(5);
    assertThat(page.totalPages()).isEqualTo(3);
  }

  /** No wallet before KYC approval, so no ledger account: an empty page, no ledger read. */
  @Test
  void beforeKycApprovalTheHistoryIsEmpty() {
    when(wallets.findWalletId(CALLER)).thenReturn(Optional.empty());

    TransactionPage page = service.list(CALLER, 2, 10);

    assertThat(page.items()).isEmpty();
    assertThat(page.page()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(10);
    assertThat(page.totalElements()).isZero();
    assertThat(page.totalPages()).isZero();
    verifyNoInteractions(ledger);
  }

  @ParameterizedTest
  @CsvSource({"-1, 20, page", "0, 0, size", "0, 101, size", "0, -5, size"})
  void pageAndSizeOutOfRangeAreValidationErrors(int page, int size, String field) {
    assertThatThrownBy(() -> service.list(CALLER, page, size))
        .isInstanceOf(ApiException.class)
        .hasMessageStartingWith(field + ":")
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
    verifyNoInteractions(wallets, ledger);
  }

  @Test
  void theBoundsOneAndOneHundredAreAccepted() {
    when(wallets.findWalletId(CALLER)).thenReturn(Optional.of(WALLET));
    when(ledger.customerHistory(eq(WALLET), anyInt(), anyInt()))
        .thenAnswer(
            i -> new LedgerHistoryPage(List.of(), i.getArgument(1), i.getArgument(2), 0, 0));

    assertThat(service.list(CALLER, 0, 1).size()).isEqualTo(1);
    assertThat(service.list(CALLER, 0, 100).size()).isEqualTo(100);
    verify(ledger).customerHistory(WALLET, 0, 100);
  }

  @Test
  void theCallerIsRequired() {
    assertThatNullPointerException().isThrownBy(() -> service.list(null, 0, 20));
    verify(wallets, never()).findWalletId(any());
  }
}
