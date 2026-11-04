package com.portfolio.wallet.wallet.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One {@code wallet.wallets} row as stored (V3). */
public record WalletRow(
    UUID id,
    String walletNumber,
    UUID customerId,
    String tier,
    String status,
    String currency,
    BigDecimal availableBalance,
    BigDecimal reservedBalance,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  /** The wallet number is the key of the recipient lookup (MVP-ADR-20), so it is left out. */
  @Override
  public String toString() {
    return "WalletRow[id=" + id + ", status=" + status + ", version=" + version + "]";
  }
}
