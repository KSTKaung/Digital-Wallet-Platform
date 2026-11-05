package com.portfolio.wallet.common.error;

/**
 * {@code 429 RATE_LIMITED} raised by a service rather than by the request filter, for limits kept
 * in the database (documentations/06 MVP-ADR-06 amendment, MVP-ADR-20). The handler adds {@code
 * Retry-After}, as the filter does (doc 06 MVP-ADR-19).
 */
public class RateLimitedException extends ApiException {

  /** The same message as the request filter's 429. */
  public static final String MESSAGE = "Too many requests";

  private final long retryAfterSeconds;

  /**
   * @param retryAfterSeconds when the caller may try again; at least 1
   */
  public RateLimitedException(long retryAfterSeconds) {
    super(ErrorCode.RATE_LIMITED, MESSAGE);
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
