package com.portfolio.wallet.architecture.fixture.wallet.domain;

import com.portfolio.wallet.architecture.fixture.wallet.web.WalletController;

/** Violation: domain depends on web. */
public class Wallet {
  WalletController controller;
}
