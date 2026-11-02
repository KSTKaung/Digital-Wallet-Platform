package com.portfolio.wallet.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record ForgotPasswordRequest(
    @NotBlank
        @Email
        @Size(max = RequestText.MAX_EMAIL_LENGTH)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        String email) {

  @Override
  public String toString() {
    return "ForgotPasswordRequest[]";
  }
}
