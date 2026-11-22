package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.application.KycStatusView;
import java.util.UUID;

/**
 * {@code GET /kyc/status} (documentations/02 §2.2); {@code status} is {@code NONE} and {@code
 * applicationId} null without one.
 */
record KycStatusResponse(
    String status, UUID applicationId, String verificationLevel, boolean walletCreated) {

  static KycStatusResponse from(KycStatusView view) {
    return new KycStatusResponse(
        view.status(), view.applicationId(), view.verificationLevel(), view.walletCreated());
  }
}
