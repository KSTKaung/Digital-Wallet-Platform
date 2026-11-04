package com.portfolio.wallet.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * {@code PUT /customers/me} (documentations/02 §2.2). "In the past" and the ISO country list are
 * checked by the service, against the application's clock. {@link #toString()} leaves the date of
 * birth out.
 */
record UpdateProfileRequest(
    @NotNull LocalDate dateOfBirth,
    @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code")
        String countryCode) {

  @Override
  public String toString() {
    return "UpdateProfileRequest[]";
  }
}
