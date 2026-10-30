package com.portfolio.wallet.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Derived balance Σ CREDIT − Σ DEBIT (documentations/01 §6.2). */
class LedgerBalanceTest {

  private static Money thb(String amount) {
    return Money.of(amount, Money.THB);
  }

  @Test
  void noEntriesIsZero() {
    assertThat(LedgerBalance.derive(Money.THB, List.of())).isEqualTo(Money.zero(Money.THB));
  }

  @Test
  void creditsMinusDebits() {
    UUID account = UUID.randomUUID();

    Money balance =
        LedgerBalance.derive(
            Money.THB,
            List.of(
                LedgerEntry.credit(account, thb("100.00")),
                LedgerEntry.debit(account, thb("30.25")),
                LedgerEntry.credit(account, thb("0.25"))));

    assertThat(balance).isEqualTo(thb("70.00"));
  }

  @Test
  void depositsAndATransferReconcileAndTheSystemAccountGoesNegative() {
    LedgerAccount cash =
        LedgerAccount.restore(
            UUID.randomUUID(),
            LedgerAccount.CASH_CLEARING_CODE,
            AccountType.SYSTEM,
            null,
            Money.THB,
            LedgerAccountStatus.ACTIVE);
    LedgerAccount alice = LedgerAccount.customer(UUID.randomUUID(), UUID.randomUUID(), Money.THB);
    LedgerAccount bob = LedgerAccount.customer(UUID.randomUUID(), UUID.randomUUID(), Money.THB);

    List<LedgerEntry> all = new ArrayList<>();
    all.addAll(PostingTemplate.deposit("D1", alice, cash, thb("500.00")).entries());
    all.addAll(PostingTemplate.transfer("T1", alice, bob, thb("120.00")).entries());
    all.addAll(PostingTemplate.withdrawal("W1", bob, cash, thb("20.00")).entries());

    assertThat(balanceOf(alice, all)).isEqualTo(thb("380.00"));
    assertThat(balanceOf(bob, all)).isEqualTo(thb("100.00"));
    assertThat(balanceOf(cash, all)).isEqualTo(thb("-480.00"));
    // Σ of every account is zero: double entry conserves money.
    assertThat(LedgerBalance.derive(Money.THB, all).isZero()).isTrue();
  }

  private static Money balanceOf(LedgerAccount account, List<LedgerEntry> entries) {
    Predicate<LedgerEntry> forAccount = e -> e.accountId().equals(account.id());
    return LedgerBalance.derive(Money.THB, entries.stream().filter(forAccount).toList());
  }
}
