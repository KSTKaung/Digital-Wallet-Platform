package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.application.KycStatusView;

/** {@code GET /kyc/status} (documentations/02 §2.2); {@code status} is {@code NONE} without one. */
record KycStatusResponse(String status, String verificationLevel, boolean walletCreated) {

  static KycStatusResponse from(KycStatusView view) {
    return new KycStatusResponse(view.status(), view.verificationLevel(), view.walletCreated());
  }
}
