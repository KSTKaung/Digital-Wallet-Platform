package com.portfolio.wallet.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** The password's rules are in the service, because its limits are configuration. */
record ChangePasswordRequest(
    @NotNull @Pattern(regexp = RequestText.ENCODABLE, message = RequestText.ENCODABLE_MESSAGE)
        String currentPassword,
    @NotNull @Pattern(regexp = RequestText.ENCODABLE, message = RequestText.ENCODABLE_MESSAGE)
        String newPassword) {

  @Override
  public String toString() {
    return "ChangePasswordRequest[]";
  }
}
