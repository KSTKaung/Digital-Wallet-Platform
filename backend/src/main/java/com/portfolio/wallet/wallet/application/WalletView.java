package com.portfolio.wallet.wallet.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The customer's own wallet (documentations/02 §2.3). Amounts have scale 2 (MVP-ADR-21). No
 * internal id: the customer addresses the wallet as "mine", and others by its number. {@link
 * #toString()} leaves the number and the balances out.
 */
public record WalletView(
    String walletNumber,
    String status,
    String tier,
    BigDecimal availableBalance,
    BigDecimal reservedBalance,
    String currency,
    Instant createdAt) {

  @Override
  public String toString() {
    return "WalletView[status=" + status + ", tier=" + tier + "]";
  }
}
