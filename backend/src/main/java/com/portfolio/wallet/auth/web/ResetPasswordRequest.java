package com.portfolio.wallet.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The password's rules are in the service, because its limits are configuration. */
record ResetPasswordRequest(
    @NotBlank @Size(max = 128) String resetToken,
    @NotNull @Pattern(regexp = RequestText.ENCODABLE, message = RequestText.ENCODABLE_MESSAGE)
        String newPassword) {

  @Override
  public String toString() {
    return "ResetPasswordRequest[]";
  }
}
