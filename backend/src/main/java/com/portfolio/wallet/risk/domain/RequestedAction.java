package com.portfolio.wallet.risk.domain;

/**
 * The action that was requested, {@code risk.risk_reviews.requested_action} (D2; V11 {@code
 * ck_risk_reviews_requested_action}); the expiry job requests none.
 */
public enum RequestedAction {
  APPROVE,
  REJECT,
  CANCEL
}
