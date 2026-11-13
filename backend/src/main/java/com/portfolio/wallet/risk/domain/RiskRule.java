package com.portfolio.wallet.risk.domain;

/**
 * The four rules of the MVP (documentations/06 MVP-ADR-24 "Score"; doc 07 D27). The name is the
 * {@code risk.risk_rule_hits.rule_code} stored for a hit, at most one per rule and assessment (V5
 * primary key), and the only values the database accepts ({@code ck_risk_rule_hits_rule_code},
 * V12). The docs name the rules but define no codes; these names were chosen in PR 5a step 3.
 *
 * <p>Rule codes are Internal, staff-only data: never logged, never shown to a customer.
 */
public enum RiskRule {
  /** Transfer amount: the amount bands. */
  TRANSFER_AMOUNT,
  /** Transfer frequency: the sender's assessments in the window, the current one included. */
  TRANSFER_FREQUENCY,
  /** New recipient: no completed transfer from this sender to this recipient yet. */
  NEW_RECIPIENT,
  /** New wallet: the sender's wallet age bands. */
  NEW_SENDER_WALLET;

  /** The stored rule code. */
  public String code() {
    return name();
  }
}
