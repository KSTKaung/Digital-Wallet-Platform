package com.portfolio.wallet.user.domain;

/**
 * The application is not in a state that allows the action: editing or submitting anything but a
 * {@code DRAFT}, or deciding anything but a {@code PENDING_REVIEW}. Maps to {@code
 * INVALID_KYC_STATE} (409, documentations/06 MVP-ADR-19 amendment).
 */
public class InvalidKycStateException extends RuntimeException {

  public InvalidKycStateException(String message) {
    super(message);
  }
}
