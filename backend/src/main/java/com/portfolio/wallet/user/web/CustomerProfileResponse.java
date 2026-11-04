package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.application.CustomerProfile;
import java.time.LocalDate;

/**
 * {@code GET /customers/me}: email and phone masked; the caller's own legal name, date of birth and
 * country as stored. {@link #toString()} leaves the personal fields out.
 */
record CustomerProfileResponse(
    String email,
    String phoneNumber,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String countryCode) {

  static CustomerProfileResponse from(CustomerProfile profile) {
    return new CustomerProfileResponse(
        profile.maskedEmail(),
        profile.maskedPhoneNumber(),
        profile.firstName(),
        profile.lastName(),
        profile.dateOfBirth(),
        profile.countryCode());
  }

  @Override
  public String toString() {
    return "CustomerProfileResponse[]";
  }
}
