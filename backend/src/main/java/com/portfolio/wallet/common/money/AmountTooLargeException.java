package com.portfolio.wallet.common.money;

/**
 * The amount has more integer digits than {@code NUMERIC(19,2)} holds ({@value
 * Money#MAX_INTEGER_DIGITS}). A separate type so a caller can tell "too large" from "too many
 * decimals" without reading the message (owner, 2026-11-04, Y3); still an {@link
 * InvalidAmountException}. The message never contains the value.
 */
public class AmountTooLargeException extends InvalidAmountException {

  public AmountTooLargeException(String message) {
    super(message);
  }
}
