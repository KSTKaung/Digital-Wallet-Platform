package com.portfolio.wallet.risk.domain;

/**
 * The staff rejection category, {@code risk.risk_reviews.reason_category} (V11 {@code
 * ck_risk_reviews_reason_category}). <b>MVP limitation (owner, 2026-11-06, S1):</b> a single
 * category; the reviewer's required text carries the detail. Every staff category maps to the
 * customer category {@code REVIEW_DECLINED} (on {@code transfer.transfers}, not here).
 */
public enum StaffRejectionCategory {
  STAFF_REJECTION
}
