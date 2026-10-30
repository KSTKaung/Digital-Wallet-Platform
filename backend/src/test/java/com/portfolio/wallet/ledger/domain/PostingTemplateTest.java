package com.portfolio.wallet.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** documentations/01 §6.4 posting templates, §6.5 invariant 6; doc 08 §4.5 test case 10. */
class PostingTemplateTest {

  private static final Money AMOUNT = Money.of("250.00", Money.THB);

  private static final LedgerAccount CASH =
      LedgerAccount.restore(
          UUID.randomUUID(),
          LedgerAccount.CASH_CLEARING_CODE,
          AccountType.SYSTEM,
          null,
          Money.THB,
          LedgerAccountStatus.ACTIVE);

  private static final LedgerAccount SUSPENSE =
      LedgerAccount.restore(
          UUID.randomUUID(),
          LedgerAccount.SUSPENSE_CODE,
          AccountType.SUSPENSE,
          null,
          Money.THB,
          LedgerAccountStatus.ACTIVE);

  private static LedgerAccount customer() {
    return LedgerAccount.customer(UUID.randomUUID(), UUID.randomUUID(), Money.THB);
  }

  private static LedgerAccount customer(LedgerAccountStatus status) {
    return LedgerAccount.restore(
        UUID.randomUUID(), null, AccountType.CUSTOMER, UUID.randomUUID(), Money.THB, status);
  }

  @Test
  void depositDebitsCashClearingAndCreditsTheCustomer() {
    LedgerAccount customer = customer();

    FinancialTransaction deposit = PostingTemplate.deposit("DEP-1", customer, CASH, AMOUNT);

    assertThat(deposit.type()).isEqualTo(TransactionType.DEPOSIT);
    assertThat(deposit.reference()).isEqualTo("DEP-1");
    assertThat(deposit.entries())
        .containsExactly(
            new LedgerEntry(CASH.id(), EntryType.DEBIT, AMOUNT),
            new LedgerEntry(customer.id(), EntryType.CREDIT, AMOUNT));
  }

  @Test
  void withdrawalDebitsTheCustomerAndCreditsCashClearing() {
    LedgerAccount customer = customer();

    FinancialTransaction withdrawal = PostingTemplate.withdrawal("WDR-1", customer, CASH, AMOUNT);

    assertThat(withdrawal.type()).isEqualTo(TransactionType.WITHDRAWAL);
    assertThat(withdrawal.entries())
        .containsExactly(
            new LedgerEntry(customer.id(), EntryType.DEBIT, AMOUNT),
            new LedgerEntry(CASH.id(), EntryType.CREDIT, AMOUNT));
  }

  @Test
  void transferDebitsTheSenderAndCreditsTheReceiver() {
    LedgerAccount sender = customer();
    LedgerAccount receiver = customer();

    FinancialTransaction transfer = PostingTemplate.transfer("TRF-1", sender, receiver, AMOUNT);

    assertThat(transfer.type()).isEqualTo(TransactionType.TRANSFER);
    assertThat(transfer.entries())
        .containsExactly(
            new LedgerEntry(sender.id(), EntryType.DEBIT, AMOUNT),
            new LedgerEntry(receiver.id(), EntryType.CREDIT, AMOUNT));
    assertThat(transfer.total()).isEqualTo(AMOUNT);
  }

  @Test
  void cashClearingMustBeTheSysCashClearingAccount() {
    assertThatThrownBy(() -> PostingTemplate.deposit("DEP-2", customer(), SUSPENSE, AMOUNT))
        .isInstanceOf(InvalidPostingException.class);
    assertThatThrownBy(() -> PostingTemplate.withdrawal("WDR-2", customer(), customer(), AMOUNT))
        .isInstanceOf(InvalidPostingException.class);
  }

  @Test
  void customerSideMustBeACustomerAccount() {
    assertThatThrownBy(() -> PostingTemplate.deposit("DEP-3", CASH, CASH, AMOUNT))
        .isInstanceOf(InvalidPostingException.class);
    assertThatThrownBy(() -> PostingTemplate.transfer("TRF-2", customer(), SUSPENSE, AMOUNT))
        .isInstanceOf(InvalidPostingException.class);
  }

  @Test
  void transferToTheSameAccountIsRejected() {
    LedgerAccount account = customer();

    assertThatThrownBy(() -> PostingTemplate.transfer("TRF-3", account, account, AMOUNT))
        .isInstanceOf(InvalidPostingException.class);
  }

  @Test
  void frozenOrClosedAccountsRejectPostings() {
    for (LedgerAccountStatus status :
        new LedgerAccountStatus[] {LedgerAccountStatus.FROZEN, LedgerAccountStatus.CLOSED}) {
      LedgerAccount inactive = customer(status);

      assertThat(inactive.acceptsPostings()).isFalse();
      assertThatThrownBy(() -> PostingTemplate.deposit("DEP-4", inactive, CASH, AMOUNT))
          .isInstanceOf(LedgerAccountNotActiveException.class);
      assertThatThrownBy(() -> PostingTemplate.transfer("TRF-4", customer(), inactive, AMOUNT))
          .isInstanceOf(LedgerAccountNotActiveException.class);
      assertThatThrownBy(() -> PostingTemplate.transfer("TRF-5", inactive, customer(), AMOUNT))
          .isInstanceOf(LedgerAccountNotActiveException.class);
    }
  }

  @Test
  void zeroAmountIsRejected() {
    assertThatThrownBy(
            () -> PostingTemplate.transfer("TRF-6", customer(), customer(), Money.zero(Money.THB)))
        .isInstanceOf(com.portfolio.wallet.common.money.InvalidAmountException.class);
  }
}
