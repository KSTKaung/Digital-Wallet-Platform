package com.portfolio.wallet.wallet.application;

import java.util.Objects;

/**
 * The response of an idempotent money operation.
 *
 * @param replayed true if it was stored by an earlier request with the same key and body, and no
 *     money moved now
 */
public record IdempotentResult(StoredResponse response, boolean replayed) {

  public IdempotentResult {
    Objects.requireNonNull(response, "response");
  }
}
