package com.portfolio.wallet.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The {@code type} of a history item from the transaction type and the side on the customer account
 * (documentations/06 MVP-ADR-11 amendment; doc 01 §6.4 postings).
 */
class AccountMovementTest {

  @ParameterizedTest
  @CsvSource({
    "DEPOSIT, CREDIT, DEPOSIT",
    "WITHDRAWAL, DEBIT, WITHDRAWAL",
    "TRANSFER, CREDIT, TRANSFER_IN",
    "TRANSFER, DEBIT, TRANSFER_OUT"
  })
  void eachMvpPostingHasOneMovement(
      TransactionType transaction, EntryType entry, AccountMovement expected) {
    assertThat(AccountMovement.of(transaction, entry)).isEqualTo(expected);
  }

  /** No MVP posting debits a depositor or credits a withdrawer; there are no reversals yet. */
  @ParameterizedTest
  @CsvSource({"DEPOSIT, DEBIT", "WITHDRAWAL, CREDIT"})
  void theOtherSideIsRefused(TransactionType transaction, EntryType entry) {
    assertThatThrownBy(() -> AccountMovement.of(transaction, entry))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(entry.name());
  }

  /** What each posting template puts on a customer account maps to that customer's movement. */
  @Test
  void thePostingTemplatesAgree() {
    LedgerAccount cash =
        LedgerAccount.restore(
            UUID.randomUUID(),
            LedgerAccount.CASH_CLEARING_CODE,
            AccountType.SYSTEM,
            null,
            Money.THB,
            LedgerAccountStatus.ACTIVE);
    LedgerAccount sender = LedgerAccount.customer(UUID.randomUUID(), UUID.randomUUID(), Money.THB);
    LedgerAccount receiver =
        LedgerAccount.customer(UUID.randomUUID(), UUID.randomUUID(), Money.THB);
    Money amount = Money.of("10.00", Money.THB);

    assertThat(movementOn(sender, PostingTemplate.deposit("D", sender, cash, amount)))
        .isEqualTo(AccountMovement.DEPOSIT);
    assertThat(movementOn(sender, PostingTemplate.withdrawal("W", sender, cash, amount)))
        .isEqualTo(AccountMovement.WITHDRAWAL);
    FinancialTransaction transfer = PostingTemplate.transfer("T", sender, receiver, amount);
    assertThat(movementOn(sender, transfer)).isEqualTo(AccountMovement.TRANSFER_OUT);
    assertThat(movementOn(receiver, transfer)).isEqualTo(AccountMovement.TRANSFER_IN);
  }

  private static AccountMovement movementOn(LedgerAccount account, FinancialTransaction posting) {
    LedgerEntry entry =
        posting.entries().stream()
            .filter(e -> e.accountId().equals(account.id()))
            .reduce((a, b) -> b)
            .orElseThrow();
    return AccountMovement.of(posting.type(), entry.type());
  }

  @Test
  void nullsAreRefused() {
    assertThatNullPointerException().isThrownBy(() -> AccountMovement.of(null, EntryType.CREDIT));
    assertThatNullPointerException()
        .isThrownBy(() -> AccountMovement.of(TransactionType.DEPOSIT, null));
  }
}
