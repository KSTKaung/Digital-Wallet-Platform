package com.portfolio.wallet.auth.domain;

/**
 * The real reason a code was refused. It goes to the audit log only: the client always sees the
 * same {@code INVALID_OTP} (documentations/06 MVP-ADR-23).
 */
public enum OtpFailure {
  NO_CHALLENGE,
  CANCELLED,
  ALREADY_CONSUMED,
  USED_UP,
  EXPIRED,
  WRONG_CODE,
  WRONG_PASSWORD,
  ACCOUNT_NOT_PENDING,
  ACCOUNT_NOT_ELIGIBLE,
  UNSUPPORTED_PURPOSE
}
