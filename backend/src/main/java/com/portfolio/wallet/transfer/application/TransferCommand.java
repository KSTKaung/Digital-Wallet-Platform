package com.portfolio.wallet.transfer.application;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code POST /transfers} request after the web layer's validation (documentations/02 §2.4; doc
 * 08 §4C.2 Q3 step 1): the body's fields as received, the sender from the JWT subject and the
 * request's remote address.
 *
 * @param senderId the JWT subject; never taken from the body (doc 08 §5, step 2 notes)
 * @param idempotencyKey the parsed {@code Idempotency-Key}
 * @param receiverWalletNumber 11 ASCII digits (format checked by the web layer)
 * @param amount and {@code currency}: checked by the service ({@code INVALID_AMOUNT}, {@code
 *     CURRENCY_MISMATCH})
 * @param description optional, at most 140 characters, no control or format characters; stored and
 *     hashed exactly as received, never logged or returned
 * @param remoteAddress the container's remote address of the request (client IP)
 */
public record TransferCommand(
    UUID senderId,
    UUID idempotencyKey,
    String receiverWalletNumber,
    BigDecimal amount,
    String currency,
    String description,
    String remoteAddress) {

  public TransferCommand {
    Objects.requireNonNull(senderId, "senderId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(receiverWalletNumber, "receiverWalletNumber");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(remoteAddress, "remoteAddress");
  }

  /** Nothing from the request: the wallet number, the key and the description stay out of logs. */
  @Override
  public String toString() {
    return "TransferCommand[]";
  }
}
