package com.portfolio.wallet.wallet.domain;

/**
 * The amount would take the day's used plus reserved total over the daily limit (documentations/06
 * MVP-ADR-18). Maps to {@code DAILY_LIMIT_EXCEEDED}.
 */
public class DailyLimitExceededException extends RuntimeException {

  public DailyLimitExceededException(String message) {
    super(message);
  }
}
