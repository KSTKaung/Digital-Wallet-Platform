package com.portfolio.wallet.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** documentations/01 §6.5 invariants 1–2; doc 08 §4.5 test case 10. */
class FinancialTransactionTest {

  private static final UUID A = UUID.randomUUID();
  private static final UUID B = UUID.randomUUID();
  private static final UUID C = UUID.randomUUID();

  private static Money thb(String amount) {
    return Money.of(amount, Money.THB);
  }

  @Test
  void balancedTwoEntryTransaction() {
    List<LedgerEntry> entries =
        new ArrayList<>(
            List.of(LedgerEntry.debit(A, thb("10.00")), LedgerEntry.credit(B, thb("10.00"))));

    FinancialTransaction transaction =
        new FinancialTransaction("TRF-1", TransactionType.TRANSFER, entries);

    assertThat(transaction.total()).isEqualTo(thb("10.00"));
    assertThat(transaction.currency()).isEqualTo(Money.THB);
    assertThat(transaction.reference()).isEqualTo("TRF-1");
    assertThat(transaction.type()).isEqualTo(TransactionType.TRANSFER);
    assertThat(transaction.entries()).hasSize(2);

    // Defensive copy: later changes to the input list do not change the transaction.
    entries.clear();
    assertThat(transaction.entries()).hasSize(2);
    assertThatThrownBy(() -> transaction.entries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void balancedMultiEntryTransaction() {
    FinancialTransaction transaction =
        new FinancialTransaction(
            "TRF-2",
            TransactionType.TRANSFER,
            List.of(
                LedgerEntry.debit(A, thb("10.00")),
                LedgerEntry.credit(B, thb("6.01")),
                LedgerEntry.credit(C, thb("3.99"))));

    assertThat(transaction.total()).isEqualTo(thb("10.00"));
  }

  @Test
  void unbalancedTransactionIsRejected() {
    assertThatThrownBy(
            () ->
                new FinancialTransaction(
                    "TRF-3",
                    TransactionType.TRANSFER,
                    List.of(
                        LedgerEntry.debit(A, thb("10.00")), LedgerEntry.credit(B, thb("9.99")))))
        .isInstanceOf(UnbalancedTransactionException.class)
        .hasMessage("debit total differs from credit total");
  }

  @Test
  void twoDebitsAreUnbalanced() {
    assertThatThrownBy(
            () ->
                new FinancialTransaction(
                    "TRF-4",
                    TransactionType.TRANSFER,
                    List.of(
                        LedgerEntry.debit(A, thb("10.00")), LedgerEntry.debit(B, thb("10.00")))))
        .isInstanceOf(UnbalancedTransactionException.class);
  }

  @Test
  void singleEntryAndEmptyTransactionsAreRejected() {
    assertThatThrownBy(
            () ->
                new FinancialTransaction(
                    "TRF-5", TransactionType.DEPOSIT, List.of(LedgerEntry.credit(A, thb("1.00")))))
        .isInstanceOf(UnbalancedTransactionException.class)
        .hasMessage("a ledger transaction needs at least 2 entries");
    assertThatThrownBy(() -> new FinancialTransaction("TRF-6", TransactionType.DEPOSIT, List.of()))
        .isInstanceOf(UnbalancedTransactionException.class);
  }

  @Test
  void entryAmountsMustBePositive() {
    assertThatThrownBy(() -> LedgerEntry.debit(A, thb("0")))
        .isInstanceOf(InvalidAmountException.class);
    assertThatThrownBy(() -> LedgerEntry.credit(A, thb("-1.00")))
        .isInstanceOf(InvalidAmountException.class);
  }

  @Test
  void referenceIsRequiredAndBounded() {
    List<LedgerEntry> entries =
        List.of(LedgerEntry.debit(A, thb("1.00")), LedgerEntry.credit(B, thb("1.00")));

    assertThatThrownBy(() -> new FinancialTransaction(" ", TransactionType.TRANSFER, entries))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FinancialTransaction("r".repeat(101), TransactionType.TRANSFER, entries))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new FinancialTransaction("r".repeat(100), TransactionType.TRANSFER, entries))
        .isNotNull();
  }
}
