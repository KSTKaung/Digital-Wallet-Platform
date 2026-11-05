package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.RequestFingerprint;
import java.util.Objects;
import java.util.UUID;

/**
 * An idempotent deposit or withdrawal: the client's {@code Idempotency-Key} and the fingerprint of
 * the validated request, which names the customer and the operation. The record's key is {@code
 * (customer, operation, key)} (documentations/02 §1.4), so another customer's identical key is a
 * separate key.
 */
public record IdempotentRequest(UUID key, RequestFingerprint fingerprint) {

  public IdempotentRequest {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(fingerprint, "fingerprint");
    // V3 ck_wallet_idempotency_records_operation; transfers have their own table (doc 07 D02).
    if (fingerprint.operation() != Operation.DEPOSIT
        && fingerprint.operation() != Operation.WITHDRAWAL) {
      throw new IllegalArgumentException("wallet idempotency covers deposits and withdrawals only");
    }
  }

  UUID customerId() {
    return fingerprint.customerId();
  }

  String operation() {
    return fingerprint.operation().name();
  }

  String requestHash() {
    return fingerprint.sha256Hex();
  }
}
