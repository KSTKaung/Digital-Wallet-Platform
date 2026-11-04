package com.portfolio.wallet.wallet.application;

import java.math.BigDecimal;

/** {@code GET /wallet/balance} (documentations/02 §2.3); amounts have scale 2 (MVP-ADR-21). */
public record WalletBalanceView(
    BigDecimal availableBalance, BigDecimal reservedBalance, String currency) {

  @Override
  public String toString() {
    return "WalletBalanceView[currency=" + currency + "]";
  }
}
