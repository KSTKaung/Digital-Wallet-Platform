package com.portfolio.wallet.user.domain;

/**
 * A field breaks a rule. The message names the field and the rule, never the value, so it can be
 * returned as a validation error (documentations/06 MVP-ADR-19).
 */
public class InvalidKycDetailsException extends RuntimeException {

  private final String field;
  private final String rule;

  public InvalidKycDetailsException(String field, String rule) {
    super(field + ": " + rule);
    this.field = field;
    this.rule = rule;
  }

  /** The rule that was broken, without the field name; never the value. */
  public String rule() {
    return rule;
  }

  public String field() {
    return field;
  }
}
