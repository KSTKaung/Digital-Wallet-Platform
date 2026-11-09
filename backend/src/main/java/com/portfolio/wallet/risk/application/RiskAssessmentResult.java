package com.portfolio.wallet.risk.application;

import java.util.Objects;
import java.util.UUID;

/**
 * A committed assessment, as the {@code transfer} module needs it: the id its transfer row
 * references and the decision. The score and the rule hits are Internal, staff-only data
 * (documentations/06 MVP-ADR-24 "Security implications") and stay in the risk schema; the staff
 * review detail (PR 5b) reads them through a staff read, so they cannot reach a customer response,
 * an email or a log through this type.
 *
 * @param assessmentId the {@code risk.risk_assessments} id; the transfer row references it
 * @param decision the band of the score
 */
public record RiskAssessmentResult(UUID assessmentId, AssessmentDecision decision) {

  public RiskAssessmentResult {
    Objects.requireNonNull(assessmentId, "assessmentId");
    Objects.requireNonNull(decision, "decision");
  }

  /** Leaves every value out: the ids and the decision are never logged. */
  @Override
  public String toString() {
    return "RiskAssessmentResult[…]";
  }
}
