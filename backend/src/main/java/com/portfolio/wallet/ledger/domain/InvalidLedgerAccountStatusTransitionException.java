package com.portfolio.wallet.ledger.domain;

/**
 * Freeze of a ledger account that is not {@code ACTIVE}, or unfreeze of one that is not {@code
 * FROZEN}. {@code CLOSED} is final (V2 {@code guard_account_update}). No documented error code
 * exists: the wallet module changes the account's status only together with its wallet's, so this
 * means the two statuses have drifted apart.
 */
public class InvalidLedgerAccountStatusTransitionException extends RuntimeException {

  public InvalidLedgerAccountStatusTransitionException(String message) {
    super(message);
  }
}
