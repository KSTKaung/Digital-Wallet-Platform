package com.portfolio.wallet.transfer.domain;

/**
 * The transfer is not in a state that allows the action: it is no longer {@code PENDING_REVIEW}, or
 * the review deadline does not allow it (documentations/06 MVP-ADR-24). Maps to {@code
 * INVALID_TRANSFER_STATE} (409, MVP-ADR-19 amendment).
 */
public class InvalidTransferStateException extends RuntimeException {

  public InvalidTransferStateException(String message) {
    super(message);
  }
}
