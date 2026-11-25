package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.application.CustomerProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * {@code GET /customers/me}: email and phone masked; the caller's own legal name, date of birth and
 * country as stored. {@link #toString()} leaves the personal fields out.
 */
record CustomerProfileResponse(
    @Schema(description = "Masked") String email,
    @Schema(nullable = true, description = "Masked; null for an account without a phone number")
        String phoneNumber,
    @Schema(nullable = true, description = "The legal name; null until a KYC approval")
        String firstName,
    @Schema(nullable = true, description = "The legal name; null until a KYC approval")
        String lastName,
    @Schema(nullable = true, description = "Null until set") LocalDate dateOfBirth,
    @Schema(nullable = true, description = "ISO 3166-1 alpha-2; null until set")
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
