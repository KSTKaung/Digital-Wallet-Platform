package com.portfolio.wallet.ledger.domain;

/**
 * A posting template was given the wrong kind of account, for example a customer account where
 * {@code SYS-CASH-CLEARING} is required, or the same account on both sides (documentations/01
 * §6.4). An internal error ({@code SYSTEM_ERROR}).
 */
public class InvalidPostingException extends RuntimeException {

  public InvalidPostingException(String message) {
    super(message);
  }
}
