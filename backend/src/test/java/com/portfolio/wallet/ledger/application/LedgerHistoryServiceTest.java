package com.portfolio.wallet.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.ledger.persistence.LedgerHistoryRepository;
import com.portfolio.wallet.ledger.persistence.LedgerHistoryRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@link LedgerHistoryService}: the customer account's entries for {@code GET /transactions}
 * (documentations/06 MVP-ADR-11 amendment; doc 07 D06).
 */
class LedgerHistoryServiceTest {

  private static final UUID WALLET = UUID.randomUUID();
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final Instant AT = Instant.parse("2026-11-14T03:00:00Z");

  private final LedgerHistoryRepository repository = mock(LedgerHistoryRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final LedgerHistoryService service = new LedgerHistoryService(repository, transactions);

  {
    when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
  }

  private static LedgerHistoryRow row(String type, String side, String amount) {
    return new LedgerHistoryRow(
        "REF-" + type + side, type, side, new BigDecimal(amount), "THB", AT);
  }

  private void stored(long total, List<LedgerHistoryRow> rows) {
    when(repository.findCustomerAccountId(WALLET)).thenReturn(Optional.of(ACCOUNT));
    when(repository.countEntries(ACCOUNT)).thenReturn(total);
    when(repository.pageEntries(any(), anyInt(), anyLong())).thenReturn(rows);
  }

  @Test
  void eachEntryBecomesAnItemWithItsMovement() {
    stored(
        4,
        List.of(
            row("TRANSFER", "CREDIT", "40"),
            row("TRANSFER", "DEBIT", "100.5"),
            row("WITHDRAWAL", "DEBIT", "20.00"),
            row("DEPOSIT", "CREDIT", "1000.00")));

    LedgerHistoryPage page = service.customerHistory(WALLET, 0, 20);

    assertThat(page.items())
        .extracting(LedgerHistoryItem::movement)
        .containsExactly("TRANSFER_IN", "TRANSFER_OUT", "WITHDRAWAL", "DEPOSIT");
    LedgerHistoryItem first = page.items().getFirst();
    assertThat(first.reference()).isEqualTo("REF-TRANSFERCREDIT");
    assertThat(first.amount()).isEqualTo(new BigDecimal("40.00"));
    assertThat(first.currency()).isEqualTo("THB");
    assertThat(first.postedAt()).isEqualTo(AT);
    assertThat(page.items().get(1).amount()).isEqualTo(new BigDecimal("100.50"));
    assertThat(page.totalElements()).isEqualTo(4);
    assertThat(page.totalPages()).isEqualTo(1);
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(20);
  }

  @Test
  void theCountThePageAndTheAccountShareOneReadOnlyRepeatableReadSnapshot() {
    stored(0, List.of());

    service.customerHistory(WALLET, 0, 5);

    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().isReadOnly()).isTrue();
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    verify(transactions).commit(any());
  }

  @Test
  void theOffsetIsPageTimesSizeAndTotalPagesRoundsUp() {
    stored(201, List.of());

    LedgerHistoryPage page = service.customerHistory(WALLET, 3, 50);

    verify(repository).pageEntries(ACCOUNT, 50, 150L);
    assertThat(page.totalPages()).isEqualTo(5);
    assertThat(page.items()).isEmpty();
  }

  /** A large page number does not overflow the offset. */
  @Test
  void theOffsetIsComputedInLong() {
    stored(0, List.of());

    service.customerHistory(WALLET, Integer.MAX_VALUE, 100);

    verify(repository).pageEntries(ACCOUNT, 100, (long) Integer.MAX_VALUE * 100);
  }

  @Test
  void aWalletWithoutAnAccountIsAnInternalError() {
    when(repository.findCustomerAccountId(WALLET)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.customerHistory(WALLET, 0, 20))
        .isInstanceOf(IllegalStateException.class);
    verify(repository, never()).countEntries(any());
  }

  @Test
  void anEntryNoMvpPostingProducesIsNeverShown() {
    stored(1, List.of(row("DEPOSIT", "DEBIT", "1.00")));

    assertThatThrownBy(() -> service.customerHistory(WALLET, 0, 20))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anUnknownTransactionTypeIsNeverShown() {
    stored(1, List.of(row("REVERSAL", "DEBIT", "1.00")));

    assertThatThrownBy(() -> service.customerHistory(WALLET, 0, 20))
        .isInstanceOf(IllegalStateException.class);
  }

  /** MVP-ADR-21: never rounded, THB only; V2 makes both unreachable. */
  @Test
  void amountsAreNeverRoundedAndOnlyThb() {
    assertThatThrownBy(() -> LedgerHistoryService.item(row("DEPOSIT", "CREDIT", "1.005")))
        .isInstanceOf(InvalidAmountException.class);
    assertThatThrownBy(
            () ->
                LedgerHistoryService.item(
                    new LedgerHistoryRow(
                        "R", "DEPOSIT", "CREDIT", new BigDecimal("1.00"), "USD", AT)))
        .isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void pageAndSizeAreChecked() {
    assertThatIllegalArgumentException().isThrownBy(() -> service.customerHistory(WALLET, -1, 20));
    assertThatIllegalArgumentException().isThrownBy(() -> service.customerHistory(WALLET, 0, 0));
    verifyNoInteractions(repository, transactions);
  }
}
