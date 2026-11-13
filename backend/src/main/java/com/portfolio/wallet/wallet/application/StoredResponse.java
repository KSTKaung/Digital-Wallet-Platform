package com.portfolio.wallet.wallet.application;

import java.util.Objects;

/**
 * The success response of a money operation, as stored on its idempotency record and replayed.
 *
 * <p>The wallet's copy of {@link com.portfolio.wallet.common.idempotency.StoredResponse}, with the
 * same rules: it exists only so the PR 4b tests (such as {@code IdempotencyServiceIT}) stay
 * unchanged, and {@link IdempotencyService} converts between the two. {@code
 * StoredResponseCopiesTest} keeps the two in step.
 *
 * @param status a 2xx HTTP status, such as 201
 * @param jsonBody the response body as JSON. Stored as {@code jsonb}, which keeps the content but
 *     not key order or whitespace: a replay should read it back into the response type
 */
public record StoredResponse(int status, String jsonBody) {

  public StoredResponse {
    if (status < 200 || status > 299) {
      throw new IllegalArgumentException("a stored success response needs a 2xx status");
    }
    Objects.requireNonNull(jsonBody, "jsonBody");
  }
}
