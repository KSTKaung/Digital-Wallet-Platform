package com.portfolio.wallet.common.idempotency;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The key of one idempotency record and the hash of the request that used it (documentations/02
 * §1.4). The record's key is {@code (customer, operation, key)}, so another customer's identical
 * key, or the same key for another operation, is a separate key. Each module builds this from its
 * own request type, which restricts the operation to those its table accepts.
 *
 * @param customerId the authenticated customer
 * @param operation the operation stored in the record ({@code varchar(20)}), such as {@code
 *     DEPOSIT} or {@code TRANSFER}
 * @param key the client's {@code Idempotency-Key}
 * @param requestHash SHA-256 of the canonical validated request: 64 lower-case hex characters, as
 *     the tables' {@code request_hash} checks require
 */
public record IdempotencyRequest(UUID customerId, String operation, UUID key, String requestHash) {

  private static final Pattern OPERATION = Pattern.compile("[A-Z][A-Z_]{0,19}");
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  public IdempotencyRequest {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(requestHash, "requestHash");
    if (!OPERATION.matcher(operation).matches()) {
      throw new IllegalArgumentException("invalid idempotency operation");
    }
    if (!SHA256_HEX.matcher(requestHash).matches()) {
      throw new IllegalArgumentException("request hash must be 64 lower-case hex characters");
    }
  }

  /** Names the operation only: the key and the hash stay out of logs and messages. */
  @Override
  public String toString() {
    return "IdempotencyRequest[operation=" + operation + "]";
  }
}
