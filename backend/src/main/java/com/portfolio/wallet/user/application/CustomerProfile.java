package com.portfolio.wallet.user.application;

import java.time.LocalDate;

/**
 * The caller's own profile. Email and phone are masked (documentations/08 §4B.2 Q15); the legal
 * name is the caller's own and is shown as stored, null until a KYC approval. {@link #toString()}
 * leaves the personal fields out.
 */
public record CustomerProfile(
    String maskedEmail,
    String maskedPhoneNumber,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String countryCode) {

  @Override
  public String toString() {
    return "CustomerProfile[]";
  }
}
