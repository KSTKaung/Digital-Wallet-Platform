package com.portfolio.wallet.wallet.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One wallet of a transfer as the wallet module read it: committed now (the pre-check) or under its
 * row lock ({@link LockedTransferWallets}). Amounts have scale 2 (MVP-ADR-21).
 *
 * @param status {@code ACTIVE}, {@code FROZEN} or {@code CLOSED}
 * @param tier {@code BASIC} or {@code VERIFIED}; selects the daily transfer limit (MVP-ADR-18)
 * @param version the row's optimistic-lock version as read (MVP-ADR-08)
 * @param createdAt the database's {@code created_at} (V3 trigger): the sender wallet's age for the
 *     risk assessment's new-wallet rule (MVP-ADR-24; doc 08 §5 step 3 notes)
 */
public record TransferWalletView(
    UUID walletId,
    UUID customerId,
    String status,
    String tier,
    BigDecimal availableBalance,
    BigDecimal reservedBalance,
    long version,
    Instant createdAt) {

  /** Whether the wallet can send or receive money now (doc 05 §4). */
  public boolean active() {
    return "ACTIVE".equals(status);
  }

  /** Ids and balances stay out of logs. */
  @Override
  public String toString() {
    return "TransferWalletView[status=" + status + ", tier=" + tier + "]";
  }
}
