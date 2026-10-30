package com.portfolio.wallet.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** documentations/08 §4.5 test case 10 (Wallet); doc 05 §4; doc 06 MVP-ADR-24. */
class WalletTest {

  private static final WalletNumber NUMBER = WalletNumber.parse("79927398713");

  private static Money thb(String amount) {
    return Money.of(amount, Money.THB);
  }

  private static Wallet wallet(WalletStatus status, String available, String reserved) {
    return Wallet.restore(
        UUID.randomUUID(),
        NUMBER,
        UUID.randomUUID(),
        Tier.BASIC,
        status,
        thb(available),
        thb(reserved),
        3);
  }

  private static void assertBalances(Wallet wallet, String available, String reserved) {
    assertThat(wallet.availableBalance()).isEqualTo(thb(available));
    assertThat(wallet.reservedBalance()).isEqualTo(thb(reserved));
  }

  @Test
  void opensActiveWithZeroBalances() {
    UUID id = UUID.randomUUID();
    UUID customer = UUID.randomUUID();

    Wallet wallet = Wallet.open(id, NUMBER, customer, Tier.VERIFIED, Money.THB);

    assertThat(wallet.id()).isEqualTo(id);
    assertThat(wallet.customerId()).isEqualTo(customer);
    assertThat(wallet.walletNumber()).isEqualTo(NUMBER);
    assertThat(wallet.tier()).isEqualTo(Tier.VERIFIED);
    assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
    assertThat(wallet.currency()).isEqualTo(Money.THB);
    assertThat(wallet.version()).isZero();
    assertBalances(wallet, "0", "0");
  }

  @Test
  void creditAndDebitChangeTheAvailableBalance() {
    Wallet wallet = wallet(WalletStatus.ACTIVE, "100.00", "0");

    wallet.credit(thb("50.25"));
    assertBalances(wallet, "150.25", "0");

    wallet.debit(thb("150.25"));
    assertBalances(wallet, "0", "0");
    assertThat(wallet.version()).isEqualTo(3);
  }

  @Test
  void debitNeverOverdraws() {
    Wallet wallet = wallet(WalletStatus.ACTIVE, "100.00", "50.00");

    assertThatThrownBy(() -> wallet.debit(thb("100.01")))
        .isInstanceOf(InsufficientBalanceException.class);
    assertBalances(wallet, "100.00", "50.00");
  }

  @Test
  void holdMovesAvailableToReservedAndCannotOverdraw() {
    Wallet wallet = wallet(WalletStatus.ACTIVE, "100.00", "0");

    wallet.hold(thb("40.00"));
    assertBalances(wallet, "60.00", "40.00");
    assertThat(wallet.totalBalance()).isEqualTo(thb("100.00"));

    assertThatThrownBy(() -> wallet.hold(thb("60.01")))
        .isInstanceOf(InsufficientBalanceException.class);
    assertBalances(wallet, "60.00", "40.00");
  }

  @Test
  void releaseHoldMovesReservedBackToAvailable() {
    Wallet wallet = wallet(WalletStatus.ACTIVE, "60.00", "40.00");

    wallet.releaseHold(thb("40.00"));

    assertBalances(wallet, "100.00", "0");
  }

  @Test
  void captureHoldTakesReservedMoneyOutOfTheWallet() {
    Wallet wallet = wallet(WalletStatus.ACTIVE, "60.00", "40.00");

    wallet.captureHold(thb("40.00"));

    assertBalances(wallet, "60.00", "0");
    assertThat(wallet.totalBalance()).isEqualTo(thb("60.00"));
  }

  @Test
  void cannotReleaseOrCaptureMoreThanIsReserved() {
    Wallet wallet = wallet(WalletStatus.ACTIVE, "60.00", "40.00");

    assertThatThrownBy(() -> wallet.releaseHold(thb("40.01")))
        .isInstanceOf(InsufficientReservedBalanceException.class);
    assertThatThrownBy(() -> wallet.captureHold(thb("40.01")))
        .isInstanceOf(InsufficientReservedBalanceException.class);
    assertBalances(wallet, "60.00", "40.00");
  }

  @Test
  void frozenWalletCannotTransactButCanReleaseAHold() {
    Wallet wallet = wallet(WalletStatus.FROZEN, "60.00", "40.00");

    for (Consumer<Wallet> operation : moneyOperations()) {
      assertThatThrownBy(() -> operation.accept(wallet)).isInstanceOf(WalletFrozenException.class);
    }
    assertBalances(wallet, "60.00", "40.00");

    wallet.releaseHold(thb("40.00"));
    assertBalances(wallet, "100.00", "0");
  }

  @Test
  void closedWalletCannotTransactButCanReleaseAHold() {
    Wallet wallet = wallet(WalletStatus.CLOSED, "60.00", "40.00");

    for (Consumer<Wallet> operation : moneyOperations()) {
      assertThatThrownBy(() -> operation.accept(wallet)).isInstanceOf(WalletClosedException.class);
    }
    assertThatThrownBy(wallet::freeze).isInstanceOf(InvalidWalletStatusTransitionException.class);
    assertThatThrownBy(wallet::unfreeze).isInstanceOf(InvalidWalletStatusTransitionException.class);
    assertBalances(wallet, "60.00", "40.00");

    // A held transfer must always be able to leave review (MVP-ADR-24).
    wallet.releaseHold(thb("40.00"));
    assertBalances(wallet, "100.00", "0");
    assertThat(wallet.status()).isEqualTo(WalletStatus.CLOSED);
  }

  @Test
  void statusIsCheckedBeforeTheAmount() {
    Wallet wallet = wallet(WalletStatus.FROZEN, "0", "0");

    assertThatThrownBy(() -> wallet.debit(thb("1000.00")))
        .isInstanceOf(WalletFrozenException.class);
  }

  @Test
  void amountsMustBePositive() {
    Wallet wallet = wallet(WalletStatus.ACTIVE, "60.00", "40.00");

    for (String amount : new String[] {"0", "-1.00"}) {
      Money money = thb(amount);
      assertThatThrownBy(() -> wallet.credit(money)).isInstanceOf(InvalidAmountException.class);
      assertThatThrownBy(() -> wallet.debit(money)).isInstanceOf(InvalidAmountException.class);
      assertThatThrownBy(() -> wallet.hold(money)).isInstanceOf(InvalidAmountException.class);
      assertThatThrownBy(() -> wallet.releaseHold(money))
          .isInstanceOf(InvalidAmountException.class);
      assertThatThrownBy(() -> wallet.captureHold(money))
          .isInstanceOf(InvalidAmountException.class);
    }
    assertBalances(wallet, "60.00", "40.00");
  }

  @Test
  void freezeAndUnfreeze() {
    Wallet wallet = wallet(WalletStatus.ACTIVE, "0", "0");

    wallet.freeze();
    assertThat(wallet.status()).isEqualTo(WalletStatus.FROZEN);
    assertThatThrownBy(wallet::freeze).isInstanceOf(InvalidWalletStatusTransitionException.class);

    wallet.unfreeze();
    assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
    assertThatThrownBy(wallet::unfreeze).isInstanceOf(InvalidWalletStatusTransitionException.class);
  }

  @Test
  void restoreRejectsNegativeBalances() {
    assertThatThrownBy(() -> wallet(WalletStatus.ACTIVE, "-0.01", "0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> wallet(WalletStatus.ACTIVE, "0", "-0.01"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void openRejectsUnsupportedCurrency() {
    assertThatThrownBy(
            () ->
                Wallet.open(
                    UUID.randomUUID(),
                    NUMBER,
                    UUID.randomUUID(),
                    Tier.BASIC,
                    java.util.Currency.getInstance("USD")))
        .isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void toStringHidesBalances() {
    assertThat(wallet(WalletStatus.ACTIVE, "123.45", "0").toString()).doesNotContain("123.45");
  }

  private static Iterable<Consumer<Wallet>> moneyOperations() {
    Money one = thb("1.00");
    return java.util.List.of(
        w -> w.credit(one), w -> w.debit(one), w -> w.hold(one), w -> w.captureHold(one));
  }
}
