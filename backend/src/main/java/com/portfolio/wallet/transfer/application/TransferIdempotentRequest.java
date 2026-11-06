package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.idempotency.IdempotencyRequest;
import com.portfolio.wallet.transfer.domain.TransferRequestFingerprint;
import java.util.Objects;
import java.util.UUID;

/**
 * An idempotent transfer: the client's {@code Idempotency-Key} and the fingerprint of the validated
 * {@code POST /transfers} body, which names the sender. The record's key is {@code (customer,
 * TRANSFER, key)} (documentations/02 §1.4); deposits and withdrawals have their own table in the
 * wallet module (doc 07 D02). The transfer module's adapter of the engine's {@link
 * IdempotencyRequest}.
 */
public record TransferIdempotentRequest(UUID key, TransferRequestFingerprint fingerprint) {

  public TransferIdempotentRequest {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(fingerprint, "fingerprint");
  }

  IdempotencyRequest toEngineRequest() {
    return new IdempotencyRequest(
        fingerprint.customerId(), fingerprint.operation(), key, fingerprint.sha256Hex());
  }

  /** Names the operation only: the {@code Idempotency-Key} and the hash are never printed. */
  @Override
  public String toString() {
    return "TransferIdempotentRequest[operation=" + fingerprint.operation() + "]";
  }
}
