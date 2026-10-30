package com.portfolio.wallet.common.money;

/**
 * A currency other than the supported one, or two amounts in different currencies combined. Maps to
 * {@code CURRENCY_MISMATCH} (documentations/02 §1.7, doc 06 MVP-ADR-21).
 */
public class CurrencyMismatchException extends RuntimeException {

  public CurrencyMismatchException(String message) {
    super(message);
  }
}
