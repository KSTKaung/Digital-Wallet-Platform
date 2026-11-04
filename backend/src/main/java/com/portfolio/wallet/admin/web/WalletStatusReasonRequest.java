package com.portfolio.wallet.admin.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /admin/customers/{customerId}/freeze} and {@code /unfreeze}
 * (documentations/02 §2.7: freeze and unfreeze require a reason). The reason is audited; it is
 * never shown to the customer. At most 500 characters, as the KYC rejection reason. {@link
 * #toString()} leaves the reason out.
 */
record WalletStatusReasonRequest(
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
    return "WalletStatusReasonRequest[]";
  }
}
