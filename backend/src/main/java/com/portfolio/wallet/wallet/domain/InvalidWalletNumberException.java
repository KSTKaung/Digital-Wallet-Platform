package com.portfolio.wallet.wallet.domain;

/**
 * Text is not a valid wallet number: 11 ASCII digits with a correct Luhn check digit
 * (documentations/08 Q3). Messages never contain the rejected value.
 */
public class InvalidWalletNumberException extends RuntimeException {

  public InvalidWalletNumberException(String message) {
    super(message);
  }
}
