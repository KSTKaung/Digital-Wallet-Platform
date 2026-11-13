package com.portfolio.wallet.risk.domain;

/**
 * The decision band of a score (documentations/06 MVP-ADR-24 "Bands"), stored in {@code
 * risk.risk_assessments.decision} (V5 {@code ck_risk_assessments_decision}). The boundaries are
 * configuration ({@link RiskPolicy.Thresholds}).
 */
public enum RiskDecision {
  /** Normal atomic transfer. */
  ALLOW,
  /** Normal transfer; the assessment is flagged for monitoring. */
  MONITOR,
  /** The transfer is held for a compliance officer; no money reaches the recipient. */
  MANUAL_REVIEW,
  /** The transfer is refused and kept as {@code BLOCKED}; an investigation is opened. */
  BLOCK
}
