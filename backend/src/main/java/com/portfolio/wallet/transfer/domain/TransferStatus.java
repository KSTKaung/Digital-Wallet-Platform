package com.portfolio.wallet.transfer.domain;

/**
 * Transfer states (documentations/06 MVP-ADR-03 amendment, MVP-ADR-24; V4 {@code
 * ck_transfers_status}). Only {@link #PENDING_REVIEW} can change; every other state is final.
 */
public enum TransferStatus {
  /** Risk allow or monitor, or an approved review; the ledger is posted. */
  COMPLETED,
  /** Risk manual review: the amount is held in the sender's reserved balance. */
  PENDING_REVIEW,
  REJECTED,
  CANCELLED,
  EXPIRED,
  /** Risk block: no balance change. */
  BLOCKED;

  public boolean isFinal() {
    return this != PENDING_REVIEW;
  }
}
