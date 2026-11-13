package com.portfolio.wallet.transfer.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/v1/transfers} (documentations/02 §2.4 MVP contract decisions: {@code
 * {receiverWalletNumber, amount, currency, description?}}). The amount's size, scale and sign and
 * the currency are checked by the service, so they give {@code INVALID_AMOUNT} and {@code
 * CURRENCY_MISMATCH} rather than a generic validation error, after the validation errors here (doc
 * 02 §2.4 steps 1 and 2). {@code description} is optional, at most 140 characters, without control
 * or format characters; it is stored and part of the request hash, but never logged, audited or
 * returned. Unknown fields ({@code receiverCustomerId}, {@code beneficiaryId}) are ignored, as
 * elsewhere. {@link #toString()} leaves every value out.
 */
record CreateTransferRequest(
    @NotNull
        @Pattern(regexp = RequestText.WALLET_NUMBER, message = RequestText.WALLET_NUMBER_MESSAGE)
        String receiverWalletNumber,
    @NotNull BigDecimal amount,
    @NotNull String currency,
    @Pattern(
            regexp = RequestText.AT_MOST_140_CHARACTERS,
            message = RequestText.AT_MOST_140_CHARACTERS_MESSAGE)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        String description) {

  @Override
  public String toString() {
    return "CreateTransferRequest[]";
  }
}
