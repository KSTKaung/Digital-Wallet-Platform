package com.portfolio.wallet.wallet.domain;

/**
 * The available balance does not cover the amount; no overdraft (documentations/05 §4). Maps to
 * {@code INSUFFICIENT_BALANCE} (doc 02 §1.7).
 */
public class InsufficientBalanceException extends RuntimeException {

  public InsufficientBalanceException(String message) {
    super(message);
  }
}
