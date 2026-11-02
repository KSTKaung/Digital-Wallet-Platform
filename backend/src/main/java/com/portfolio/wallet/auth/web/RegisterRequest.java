package com.portfolio.wallet.auth.web;

import com.portfolio.wallet.auth.domain.PhoneNumber;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The password's length rule is in the service, because its limits are configuration. */
record RegisterRequest(
    @NotBlank
        @Email
        @Size(max = RequestText.MAX_EMAIL_LENGTH)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        String email,
    @NotBlank @Pattern(regexp = PhoneNumber.E164_PATTERN) String phoneNumber,
    @NotNull @Pattern(regexp = RequestText.ENCODABLE, message = RequestText.ENCODABLE_MESSAGE)
        String password) {

  @Override
  public String toString() {
    return "RegisterRequest[]";
  }
}
