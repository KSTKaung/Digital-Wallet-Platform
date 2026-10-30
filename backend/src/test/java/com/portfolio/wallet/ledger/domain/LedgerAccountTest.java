package com.portfolio.wallet.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** V2 {@code ledger.ledger_accounts} constraints, mirrored in the domain. */
class LedgerAccountTest {

  @Test
  void customerAccountReferencesTheWalletAndIsActive() {
    UUID wallet = UUID.randomUUID();

    LedgerAccount account = LedgerAccount.customer(UUID.randomUUID(), wallet, Money.THB);

    assertThat(account.type()).isEqualTo(AccountType.CUSTOMER);
    assertThat(account.ownerReference()).contains(wallet);
    assertThat(account.code()).isEmpty();
    assertThat(account.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
    assertThat(account.acceptsPostings()).isTrue();
    assertThat(account.isCashClearing()).isFalse();
  }

  @Test
  void seededSystemAccounts() {
    LedgerAccount cash =
        LedgerAccount.restore(
            UUID.randomUUID(),
            "SYS-CASH-CLEARING",
            AccountType.SYSTEM,
            null,
            Money.THB,
            LedgerAccountStatus.ACTIVE);

    assertThat(cash.isCashClearing()).isTrue();
    assertThat(cash.code()).contains("SYS-CASH-CLEARING");
    assertThat(cash.ownerReference()).isEmpty();
  }

  @Test
  void customerAccountsHaveAnOwnerAndNoCode() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                LedgerAccount.restore(
                    id, null, AccountType.CUSTOMER, null, Money.THB, LedgerAccountStatus.ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LedgerAccount.restore(
                    id,
                    "X",
                    AccountType.CUSTOMER,
                    UUID.randomUUID(),
                    Money.THB,
                    LedgerAccountStatus.ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void systemAccountsHaveACodeAndNoOwner() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                LedgerAccount.restore(
                    id, null, AccountType.SYSTEM, null, Money.THB, LedgerAccountStatus.ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LedgerAccount.restore(
                    id,
                    "SYS-X",
                    AccountType.SUSPENSE,
                    UUID.randomUUID(),
                    Money.THB,
                    LedgerAccountStatus.ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LedgerAccount.restore(
                    id,
                    "S".repeat(51),
                    AccountType.SYSTEM,
                    null,
                    Money.THB,
                    LedgerAccountStatus.ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyThb() {
    assertThatThrownBy(
            () ->
                LedgerAccount.customer(
                    UUID.randomUUID(), UUID.randomUUID(), Currency.getInstance("EUR")))
        .isInstanceOf(CurrencyMismatchException.class);
  }
}
