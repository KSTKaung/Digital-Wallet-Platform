package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.risk.domain.RiskDecision;

/**
 * The decision band of an assessment, for callers outside the risk module, which may not use {@code
 * risk.domain} (ArchUnit {@code moduleInternalsArePrivate}). Same values as the domain's {@link
 * RiskDecision} (documentations/06 MVP-ADR-24 "Bands").
 */
public enum AssessmentDecision {
  /** Normal atomic transfer. */
  ALLOW,
  /** Normal transfer; the assessment is flagged for monitoring. */
  MONITOR,
  /** The transfer is held ({@code PENDING_REVIEW}); no money reaches the recipient. */
  MANUAL_REVIEW,
  /** The transfer is kept {@code BLOCKED}, {@code 403 RISK_BLOCKED}; an investigation is open. */
  BLOCK;

  static AssessmentDecision of(RiskDecision decision) {
    return switch (decision) {
      case ALLOW -> ALLOW;
      case MONITOR -> MONITOR;
      case MANUAL_REVIEW -> MANUAL_REVIEW;
      case BLOCK -> BLOCK;
    };
  }
}
