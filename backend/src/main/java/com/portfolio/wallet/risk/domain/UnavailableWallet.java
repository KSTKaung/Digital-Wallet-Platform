package com.portfolio.wallet.risk.domain;

/**
 * Which wallet an approval found frozen or closed (D2 case 3): the only system reasons a system
 * rejection may carry, so {@code REVIEW_DEADLINE_PASSED} cannot be passed to one (V11 {@code
 * ck_risk_reviews_outcome}). The caller checks the sender's wallet first (doc 06 MVP-ADR-24).
 */
public enum UnavailableWallet {
  SENDER_WALLET_FROZEN(SystemReason.SENDER_WALLET_FROZEN),
  SENDER_WALLET_CLOSED(SystemReason.SENDER_WALLET_CLOSED),
  RECIPIENT_WALLET_FROZEN(SystemReason.RECIPIENT_WALLET_FROZEN),
  RECIPIENT_WALLET_CLOSED(SystemReason.RECIPIENT_WALLET_CLOSED);

  private final SystemReason systemReason;

  UnavailableWallet(SystemReason systemReason) {
    this.systemReason = systemReason;
  }

  public SystemReason systemReason() {
    return systemReason;
  }
}
