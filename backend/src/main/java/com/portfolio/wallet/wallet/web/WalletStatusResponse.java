package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.wallet.application.WalletStatusView;

/**
 * {@code GET /wallet/status} and the freeze response: {@code ACTIVE}, {@code FROZEN} or {@code
 * CLOSED}.
 */
record WalletStatusResponse(String status) {

  static WalletStatusResponse from(WalletStatusView view) {
    return new WalletStatusResponse(view.status());
  }
}
