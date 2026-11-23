package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.application.KycStatusView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * {@code GET /kyc/status} (documentations/02 §2.2); {@code status} is {@code NONE} and {@code
 * applicationId} null without one.
 */
record KycStatusResponse(
    @Schema(description = "NONE without an application") String status,
    @Schema(nullable = true, description = "Null without an application") UUID applicationId,
    @Schema(nullable = true, description = "Null without an application") String verificationLevel,
    boolean walletCreated) {

  static KycStatusResponse from(KycStatusView view) {
    return new KycStatusResponse(
        view.status(), view.applicationId(), view.verificationLevel(), view.walletCreated());
  }
}
