package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.wallet.application.WalletStatusView;

/** The wallet's status after a staff freeze or unfreeze: no balance and no wallet number. */
record CustomerWalletStatusResponse(String status) {

  static CustomerWalletStatusResponse from(WalletStatusView view) {
    return new CustomerWalletStatusResponse(view.status());
  }
}
