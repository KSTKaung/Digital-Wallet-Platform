package com.portfolio.wallet.auth.application;

/**
 * An account's email and phone, unmasked: the caller masks them before they leave the application
 * (documentations/08 §4B.2 Q15). {@link #toString()} leaves both out. The phone is null for staff.
 */
public record ContactDetails(String email, String phoneNumber) {

  @Override
  public String toString() {
    return "ContactDetails[]";
  }
}
