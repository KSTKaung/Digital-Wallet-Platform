package com.portfolio.wallet.wallet.domain;

/**
 * Freeze of a wallet that is not {@code ACTIVE}, or unfreeze of a wallet that is not {@code
 * FROZEN}. No documented error code exists.
 */
public class InvalidWalletStatusTransitionException extends RuntimeException {

  public InvalidWalletStatusTransitionException(String message) {
    super(message);
  }
}
