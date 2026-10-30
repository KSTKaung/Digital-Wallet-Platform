package com.portfolio.wallet.wallet.domain;

/**
 * Money operations that have limits (documentations/06 MVP-ADR-18; V3 {@code
 * ck_daily_limit_usage_operation}).
 */
public enum Operation {
  TRANSFER,
  DEPOSIT,
  WITHDRAWAL
}
