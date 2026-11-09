package com.portfolio.wallet.risk.domain;

import java.util.Objects;

/**
 * A triggered rule and the highest matching weight of that rule (documentations/06 MVP-ADR-24), one
 * {@code risk.risk_rule_hits} row (V5 {@code ck_risk_rule_hits_weight}: 1–100).
 */
public record RuleHit(RiskRule rule, int weight) {

  public RuleHit {
    Objects.requireNonNull(rule, "rule");
    RiskPolicy.requireWeight(weight, "rule hit weight");
  }

  /** Leaves the rule and weight out: rule details are staff-only and never logged. */
  @Override
  public String toString() {
    return "RuleHit[…]";
  }
}
