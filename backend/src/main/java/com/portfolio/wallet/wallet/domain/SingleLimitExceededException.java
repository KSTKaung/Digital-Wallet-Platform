package com.portfolio.wallet.wallet.domain;

/**
 * The amount is above the per-transaction maximum (documentations/06 MVP-ADR-18). Maps to {@code
 * SINGLE_LIMIT_EXCEEDED}.
 */
public class SingleLimitExceededException extends RuntimeException {

  public SingleLimitExceededException(String message) {
    super(message);
  }
}
