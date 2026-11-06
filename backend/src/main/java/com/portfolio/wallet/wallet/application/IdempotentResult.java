package com.portfolio.wallet.wallet.application;

import java.util.Objects;

/**
 * The response of an idempotent money operation.
 *
 * <p>The wallet's copy of {@link com.portfolio.wallet.common.idempotency.IdempotentResult}, with
 * the same rules: it exists only so the PR 4b tests (such as {@code IdempotencyServiceIT}) stay
 * unchanged, and {@link IdempotencyService} converts between the two. {@code
 * StoredResponseCopiesTest} keeps the two in step.
 *
 * @param replayed true if it was stored by an earlier request with the same key and body, and no
 *     money moved now
 */
public record IdempotentResult(StoredResponse response, boolean replayed) {

  public IdempotentResult {
    Objects.requireNonNull(response, "response");
  }
}
