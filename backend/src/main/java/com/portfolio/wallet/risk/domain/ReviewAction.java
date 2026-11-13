package com.portfolio.wallet.risk.domain;

/**
 * The actual outcome of a review row, {@code risk.risk_reviews.action} (documentations/06
 * MVP-ADR-24 amendment "Review outcomes", D2; V5 {@code ck_risk_reviews_action}).
 */
public enum ReviewAction {
  APPROVE,
  REJECT,
  CANCEL,
  EXPIRE
}
