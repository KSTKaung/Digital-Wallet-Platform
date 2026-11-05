package com.portfolio.wallet.wallet.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/v1/wallet/deposits} (documentations/02 §2.3). The amount's scale, sign
 * and the currency are checked by the service, so they give {@code INVALID_AMOUNT} and {@code
 * CURRENCY_MISMATCH} rather than a generic validation error (MVP-ADR-21). {@code reference} is the
 * client's optional free-text note (doc 07 D10; V3 {@code client_reference varchar(100)}); it is
 * stored, never shown in logs or audit rows. {@link #toString()} leaves the note out.
 *
 * <p>{@code fundingSource} is refused (owner, 2026-11-04, W3): cards and banks are out of the MVP
 * (MVP-ADR-12, -13), and a client that sends one must not believe it was used. Declared as {@link
 * Object}, so any JSON value (string, number, boolean, array, object) binds and fails {@link Null}
 * with the same {@code 400 VALIDATION_ERROR} naming the field; an explicit JSON {@code null} binds
 * to null and counts as absent. Only this endpoint is affected: the global Jackson setting that
 * ignores unknown properties stays as it is. The field never reaches the service or the request
 * hash.
 */
record DepositRequest(
    @NotNull BigDecimal amount,
    @NotNull String currency,
    @Size(max = 100)
        @Pattern(
            regexp = RequestText.NO_CONTROL_CHARACTERS,
            message = RequestText.NO_CONTROL_CHARACTERS_MESSAGE)
        @Pattern(
            regexp = RequestText.HAS_LETTER_OR_DIGIT,
            message = RequestText.HAS_LETTER_OR_DIGIT_MESSAGE)
        String reference,
    @Null(message = "is not supported") Object fundingSource) {

  @Override
  public String toString() {
    return "DepositRequest[]";
  }
}
