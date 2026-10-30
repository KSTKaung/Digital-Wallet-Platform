package com.portfolio.wallet.common.money;

/**
 * An amount is not acceptable: more than 2 decimal places, too many integer digits, not positive
 * where a positive amount is required, or below a minimum. Maps to {@code INVALID_AMOUNT}
 * (documentations/02 §1.7, doc 06 MVP-ADR-18, -21).
 *
 * <p>Messages never contain the rejected value.
 */
public class InvalidAmountException extends RuntimeException {

  public InvalidAmountException(String message) {
    super(message);
  }
}
