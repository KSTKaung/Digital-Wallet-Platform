package com.portfolio.wallet.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  /** Every code listed in documentations/02 §1.7. */
  private static final List<String> DOCUMENTED =
      List.of(
          "INSUFFICIENT_BALANCE",
          "DAILY_LIMIT_EXCEEDED",
          "SINGLE_LIMIT_EXCEEDED",
          "WALLET_FROZEN",
          "WALLET_NOT_FOUND",
          "SELF_TRANSFER",
          "BENEFICIARY_BLOCKED",
          "RISK_BLOCKED",
          "INVALID_AMOUNT",
          "CURRENCY_MISMATCH",
          "KYC_REQUIRED",
          "KYC_ALREADY_ACTIVE",
          "INVALID_OTP",
          "OTP_EXPIRED",
          "OTP_ATTEMPTS_EXCEEDED",
          "ACCOUNT_LOCKED",
          "IDEMPOTENCY_KEY_REUSED",
          "SERVICE_DEGRADED",
          "QR_EXPIRED",
          "MERCHANT_NOT_APPROVED",
          "REFUND_EXCEEDS_PAYMENT",
          "CARD_NOT_FOUND",
          "CARD_DECLINED",
          "CARD_EXPIRED",
          "BILLER_UNAVAILABLE",
          "BILL_INQUIRY_FAILED",
          "BILL_PAYMENT_PENDING",
          "FACE_MISMATCH",
          "LIVENESS_FAILED",
          "VERIFICATION_ATTEMPTS_EXCEEDED",
          "CONSENT_REQUIRED",
          "SYSTEM_ERROR");

  @Test
  void containsEveryDocumentedCode() {
    List<String> names = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();

    assertThat(names).containsAll(DOCUMENTED);
  }

  @Test
  void mapsStatusesFromTheDocumentedErrorTable() {
    assertThat(ErrorCode.INSUFFICIENT_BALANCE.status().value()).isEqualTo(409);
    assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.status().value()).isEqualTo(409);
    assertThat(ErrorCode.KYC_REQUIRED.status().value()).isEqualTo(403);
    assertThat(ErrorCode.WALLET_NOT_FOUND.status().value()).isEqualTo(404);
    assertThat(ErrorCode.SERVICE_DEGRADED.status().value()).isEqualTo(503);
    assertThat(ErrorCode.SYSTEM_ERROR.status().value()).isEqualTo(500);
  }
}
