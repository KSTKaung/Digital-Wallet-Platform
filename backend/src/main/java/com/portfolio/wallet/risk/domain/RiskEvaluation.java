package com.portfolio.wallet.risk.domain;

import java.util.List;
import java.util.Objects;

/**
 * The result of {@link RiskPolicy#evaluate}: the score (the sum of the hits' weights, capped at
 * 100), its decision band and the triggered rules, one hit per rule.
 */
public record RiskEvaluation(int score, RiskDecision decision, List<RuleHit> hits) {

  public RiskEvaluation {
    Objects.requireNonNull(decision, "decision");
    hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
    if (score < 0 || score > RiskPolicy.MAX_SCORE) {
      throw new IllegalArgumentException("score must be between 0 and 100");
    }
  }

  /** Leaves the score, decision and hits out: they are staff-only and never logged. */
  @Override
  public String toString() {
    return "RiskEvaluation[…]";
  }
}
