package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.wallet.application.WalletBalanceView;
import java.math.BigDecimal;

/** {@code GET /wallet/balance} (documentations/02 §2.3). */
record WalletBalanceResponse(
    BigDecimal availableBalance, BigDecimal reservedBalance, String currency) {

  static WalletBalanceResponse from(WalletBalanceView view) {
    return new WalletBalanceResponse(
        view.availableBalance(), view.reservedBalance(), view.currency());
  }

  @Override
  public String toString() {
    return "WalletBalanceResponse[currency=" + currency + "]";
  }
}
