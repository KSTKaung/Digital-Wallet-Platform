package com.portfolio.wallet.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code purpose} is a string, so {@code LOGIN} reaches the service and gets the same {@code
 * INVALID_OTP} as any other failure; a value outside the contract is a plain validation error that
 * depends on no account. Shape checks on {@code otp} stay out, so a malformed code is simply wrong.
 * The same goes for {@code password}: {@code REGISTRATION} needs it, and without it the request
 * fails like one with a wrong password. {@code PASSWORD_RESET} ignores it.
 */
record VerifyOtpRequest(
    @NotBlank @Size(max = 64) String otp,
    @NotBlank @Pattern(regexp = "REGISTRATION|PASSWORD_RESET|LOGIN") String purpose,
    @NotBlank
        @Size(max = RequestText.MAX_EMAIL_LENGTH)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        String identifier,
    @Pattern(regexp = RequestText.ENCODABLE, message = RequestText.ENCODABLE_MESSAGE)
        String password) {

  @Override
  public String toString() {
    return "VerifyOtpRequest[]";
  }
}
