package com.portfolio.wallet.transfer.domain;

/**
 * Customer input of a transfer is invalid, for example a description longer than 140 characters
 * (documentations/08 Q4). Maps to {@code VALIDATION_ERROR}. Messages name the field, never the
 * value (MVP-ADR-19).
 */
public class InvalidTransferDetailsException extends RuntimeException {

  public InvalidTransferDetailsException(String message) {
    super(message);
  }
}
