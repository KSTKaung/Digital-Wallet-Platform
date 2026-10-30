package com.portfolio.wallet.wallet.domain;

/**
 * Outcome of a limit check (documentations/06 MVP-ADR-18). Rules are checked in this order; the
 * first one that fails is reported.
 */
public enum LimitCheckResult {
  WITHIN_LIMITS,
  /** Below the minimum: {@code 400 INVALID_AMOUNT}. */
  BELOW_MINIMUM,
  /** Above the per-transaction maximum: {@code 409 SINGLE_LIMIT_EXCEEDED}. */
  SINGLE_LIMIT_EXCEEDED,
  /** Used + reserved + amount above the daily limit: {@code 409 DAILY_LIMIT_EXCEEDED}. */
  DAILY_LIMIT_EXCEEDED
}
