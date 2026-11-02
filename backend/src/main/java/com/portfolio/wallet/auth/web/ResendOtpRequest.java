package com.portfolio.wallet.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record ResendOtpRequest(
    @NotBlank
        @Size(max = RequestText.MAX_EMAIL_LENGTH)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        String identifier,
    @NotBlank @Pattern(regexp = "REGISTRATION|PASSWORD_RESET|LOGIN") String purpose) {

  @Override
  public String toString() {
    return "ResendOtpRequest[]";
  }
}
