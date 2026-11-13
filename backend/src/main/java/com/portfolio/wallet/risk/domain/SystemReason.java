package com.portfolio.wallet.risk.domain;

/**
 * Why the system decided, {@code risk.risk_reviews.system_reason} (D2; V11 {@code
 * ck_risk_reviews_system_reason}): one of the four wallet reasons of a system rejection, or {@code
 * REVIEW_DEADLINE_PASSED} of an expiry.
 */
public enum SystemReason {
  SENDER_WALLET_FROZEN,
  SENDER_WALLET_CLOSED,
  RECIPIENT_WALLET_FROZEN,
  RECIPIENT_WALLET_CLOSED,
  REVIEW_DEADLINE_PASSED
}
