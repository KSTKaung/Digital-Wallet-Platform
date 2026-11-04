package com.portfolio.wallet.admin.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /admin/kyc/applications/{id}/reject} (documentations/02 §2.7). The customer
 * sees the reason, so it must not contain identity details; one that repeats the application's
 * document reference is refused by the service. The size is V8's column size. {@link #toString()}
 * leaves the reason out.
 */
record RejectKycRequest(
    @NotBlank
        @Size(max = 500)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        @Pattern(
            regexp = RequestText.HAS_LETTER_OR_DIGIT,
            message = RequestText.HAS_LETTER_OR_DIGIT_MESSAGE)
        String reason) {

  @Override
  public String toString() {
    return "RejectKycRequest[]";
  }
}
