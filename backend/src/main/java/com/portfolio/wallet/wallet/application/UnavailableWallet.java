package com.portfolio.wallet.wallet.application;

/**
 * Which wallet made an approval release the hold, and why (MVP-ADR-24 review case 3). The values
 * match the system reasons {@code SENDER_WALLET_FROZEN}, {@code SENDER_WALLET_CLOSED}, {@code
 * RECIPIENT_WALLET_FROZEN} and {@code RECIPIENT_WALLET_CLOSED} of {@code risk.risk_reviews}; the
 * caller maps them, because the wallet module does not depend on {@code risk}.
 */
public enum UnavailableWallet {
  SENDER_FROZEN,
  SENDER_CLOSED,
  RECIPIENT_FROZEN,
  RECIPIENT_CLOSED;

  /**
   * The sender's wallet is checked first (MVP-ADR-24 review case 3).
   *
   * @return null if both are {@code ACTIVE}
   */
  static UnavailableWallet of(String senderStatus, String recipientStatus) {
    UnavailableWallet sender = of(senderStatus, SENDER_FROZEN, SENDER_CLOSED);
    return sender != null ? sender : of(recipientStatus, RECIPIENT_FROZEN, RECIPIENT_CLOSED);
  }

  private static UnavailableWallet of(
      String status, UnavailableWallet frozen, UnavailableWallet closed) {
    return switch (status) {
      case "ACTIVE" -> null;
      case "FROZEN" -> frozen;
      case "CLOSED" -> closed;
      default -> throw new IllegalStateException("unknown wallet status");
    };
  }
}
