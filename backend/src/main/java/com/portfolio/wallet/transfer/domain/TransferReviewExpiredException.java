package com.portfolio.wallet.transfer.domain;

/**
 * Approve, reject or cancel of a held transfer whose review deadline has passed. The caller expires
 * the transfer in the same database transaction and returns {@code INVALID_TRANSFER_STATE}
 * (documentations/06 MVP-ADR-24).
 */
public class TransferReviewExpiredException extends InvalidTransferStateException {

  public TransferReviewExpiredException(String message) {
    super(message);
  }
}
