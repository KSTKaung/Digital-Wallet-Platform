package com.portfolio.wallet.transfer.domain;

/**
 * Sender and receiver are the same customer or the same wallet (documentations/05 §4). Maps to
 * {@code SELF_TRANSFER} (doc 02 §1.7).
 */
public class SelfTransferException extends RuntimeException {

  public SelfTransferException(String message) {
    super(message);
  }
}
