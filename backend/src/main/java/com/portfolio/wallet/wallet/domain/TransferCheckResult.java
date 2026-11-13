package com.portfolio.wallet.wallet.domain;

/**
 * Outcome of the sender-side checks of an outgoing transfer ({@link TransferRules}), in the order
 * they are checked (documentations/08 §4C.2 Q3 step 5; doc 02 §2.4 error step 6). The first rule
 * that fails is reported.
 */
public enum TransferCheckResult {
  ALLOWED,
  /** {@code 409 WALLET_FROZEN}. */
  SENDER_FROZEN,
  /**
   * The sender wallet is {@code CLOSED}: no documented business code; {@code 500} like the money
   * endpoints today (doc 07 D21, owner 2026-11-04, Z8).
   */
  SENDER_CLOSED,
  /** {@code 400 INVALID_AMOUNT}. */
  BELOW_MINIMUM,
  /** {@code 409 SINGLE_LIMIT_EXCEEDED}. */
  SINGLE_LIMIT_EXCEEDED,
  /** Used + reserved + amount above the tier's daily limit: {@code 409 DAILY_LIMIT_EXCEEDED}. */
  DAILY_LIMIT_EXCEEDED,
  /** {@code 409 INSUFFICIENT_BALANCE}: the available balance only; no overdraft. */
  INSUFFICIENT_BALANCE
}
