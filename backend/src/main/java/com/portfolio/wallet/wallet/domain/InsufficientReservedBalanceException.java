package com.portfolio.wallet.wallet.domain;

/**
 * Releasing or capturing more than the reserved balance holds (documentations/06 MVP-ADR-24). An
 * internal invariant violation with no documented error code: the reserved amount always comes from
 * a held transfer, so this indicates a bug or inconsistent data ({@code SYSTEM_ERROR}).
 */
public class InsufficientReservedBalanceException extends RuntimeException {

  public InsufficientReservedBalanceException(String message) {
    super(message);
  }
}
