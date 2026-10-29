package com.portfolio.wallet.common.error;

import org.springframework.http.HttpStatus;

/**
 * API error codes (documentations/02 §1.7) with their default HTTP status.
 *
 * <p>{@link #VALIDATION_ERROR}, {@link #UNAUTHORIZED}, {@link #FORBIDDEN}, {@link #NOT_FOUND} and
 * {@link #METHOD_NOT_ALLOWED} are MVP additions for generic framework errors that doc 02 maps to a
 * status but does not name (documentations/06, pending approval).
 */
public enum ErrorCode {
  // Generic (MVP additions)
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
  FORBIDDEN(HttpStatus.FORBIDDEN),
  NOT_FOUND(HttpStatus.NOT_FOUND),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

  // Wallet and money movement
  INSUFFICIENT_BALANCE(HttpStatus.CONFLICT),
  DAILY_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
  SINGLE_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
  WALLET_FROZEN(HttpStatus.CONFLICT),
  WALLET_NOT_FOUND(HttpStatus.NOT_FOUND),
  SELF_TRANSFER(HttpStatus.CONFLICT),
  BENEFICIARY_BLOCKED(HttpStatus.CONFLICT),
  RISK_BLOCKED(HttpStatus.FORBIDDEN),
  INVALID_AMOUNT(HttpStatus.BAD_REQUEST),
  CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST),
  IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
  SERVICE_DEGRADED(HttpStatus.SERVICE_UNAVAILABLE),

  // KYC
  KYC_REQUIRED(HttpStatus.FORBIDDEN),
  KYC_ALREADY_ACTIVE(HttpStatus.CONFLICT),

  // Authentication
  INVALID_OTP(HttpStatus.BAD_REQUEST),
  OTP_EXPIRED(HttpStatus.BAD_REQUEST),
  OTP_ATTEMPTS_EXCEEDED(HttpStatus.BAD_REQUEST),
  ACCOUNT_LOCKED(HttpStatus.FORBIDDEN),

  // Payments, cards, bills and face verification (target scope; out of MVP scope, kept for
  // contract completeness)
  QR_EXPIRED(HttpStatus.CONFLICT),
  MERCHANT_NOT_APPROVED(HttpStatus.CONFLICT),
  REFUND_EXCEEDS_PAYMENT(HttpStatus.CONFLICT),
  CARD_NOT_FOUND(HttpStatus.NOT_FOUND),
  CARD_DECLINED(HttpStatus.CONFLICT),
  CARD_EXPIRED(HttpStatus.CONFLICT),
  BILLER_UNAVAILABLE(HttpStatus.CONFLICT),
  BILL_INQUIRY_FAILED(HttpStatus.BAD_GATEWAY),
  BILL_PAYMENT_PENDING(HttpStatus.CONFLICT),
  FACE_MISMATCH(HttpStatus.CONFLICT),
  LIVENESS_FAILED(HttpStatus.CONFLICT),
  VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
  CONSENT_REQUIRED(HttpStatus.FORBIDDEN),

  SYSTEM_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

  private final HttpStatus status;

  ErrorCode(HttpStatus status) {
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
