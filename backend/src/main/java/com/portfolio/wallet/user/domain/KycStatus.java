package com.portfolio.wallet.user.domain;

/** KYC application states (documentations/02 §2.2): DRAFT, PENDING_REVIEW, APPROVED, REJECTED. */
public enum KycStatus {
  DRAFT,
  PENDING_REVIEW,
  APPROVED,
  REJECTED;

  /** Counts towards the one active application per customer; a rejected customer may re-apply. */
  public boolean isActive() {
    return this != REJECTED;
  }

  public boolean isFinal() {
    return this == APPROVED || this == REJECTED;
  }
}
