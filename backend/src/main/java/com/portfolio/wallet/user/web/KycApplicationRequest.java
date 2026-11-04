package com.portfolio.wallet.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /kyc/applications} and {@code PUT /kyc/applications/{id}} (documentations/02
 * §2.2). The enums are strings, so a value outside the contract is a validation error that names
 * the field. Sizes are the V8 column sizes. {@link #toString()} leaves the KYC content out.
 */
record KycApplicationRequest(
    @NotBlank @Pattern(regexp = "BASIC|FULL", message = "must be BASIC or FULL")
        String verificationLevel,
    @NotBlank @Pattern(regexp = "ID_CARD|PASSPORT", message = "must be ID_CARD or PASSPORT")
        String documentType,
    @NotBlank
        @Size(max = 50)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        String documentReference,
    @NotBlank @Size(max = 100) @LegalNamePart String firstName,
    @NotBlank @Size(max = 100) @LegalNamePart String lastName) {

  @Override
  public String toString() {
    return "KycApplicationRequest[]";
  }
}
