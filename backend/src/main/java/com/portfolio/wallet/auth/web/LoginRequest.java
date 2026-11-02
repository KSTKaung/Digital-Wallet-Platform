package com.portfolio.wallet.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record LoginRequest(
    @NotBlank
        @Size(max = RequestText.MAX_EMAIL_LENGTH)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        String email,
    @NotNull @Pattern(regexp = RequestText.ENCODABLE, message = RequestText.ENCODABLE_MESSAGE)
        String password) {

  @Override
  public String toString() {
    return "LoginRequest[]";
  }
}
