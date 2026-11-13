package com.portfolio.wallet.common.text;

/**
 * A text field breaks a rule of {@link TextRules}. The message names the field and the rule, never
 * the value, so it can be returned as a validation error (documentations/06 MVP-ADR-19).
 */
public class InvalidTextException extends RuntimeException {

  private final String field;
  private final String rule;

  public InvalidTextException(String field, String rule) {
    super(field + ": " + rule);
    this.field = field;
    this.rule = rule;
  }

  public String field() {
    return field;
  }

  /** The rule that was broken, without the field name; never the value. */
  public String rule() {
    return rule;
  }
}
