package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.wallet.domain.Tier;
import com.portfolio.wallet.wallet.domain.Wallet;
import com.portfolio.wallet.wallet.domain.WalletNumber;
import com.portfolio.wallet.wallet.domain.WalletStatus;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.util.Currency;

/** Stored wallet rows to the domain: the domain rechecks every stored value (MVP-ADR-07). */
final class WalletRows {

  private WalletRows() {}

  static Wallet toDomain(WalletRow row) {
    Currency currency = Currency.getInstance(row.currency());
    return Wallet.restore(
        row.id(),
        WalletNumber.parse(row.walletNumber()),
        row.customerId(),
        Tier.valueOf(row.tier()),
        WalletStatus.valueOf(row.status()),
        Money.of(row.availableBalance(), currency),
        Money.of(row.reservedBalance(), currency),
        row.version());
  }
}
