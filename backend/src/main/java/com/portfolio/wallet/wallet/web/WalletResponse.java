package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.wallet.application.WalletView;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code GET /wallet} (documentations/02 §2.3). Money as JSON numbers with 2 decimals (doc 02 §1.6;
 * MVP-ADR-21); no internal id. {@link #toString()} leaves the number and the balances out.
 */
record WalletResponse(
    String walletNumber,
    String status,
    String tier,
    BigDecimal availableBalance,
    BigDecimal reservedBalance,
    String currency,
    Instant createdAt) {

  static WalletResponse from(WalletView view) {
    return new WalletResponse(
        view.walletNumber(),
        view.status(),
        view.tier(),
        view.availableBalance(),
        view.reservedBalance(),
        view.currency(),
        view.createdAt());
  }

  @Override
  public String toString() {
    return "WalletResponse[status=" + status + ", tier=" + tier + "]";
  }
}
