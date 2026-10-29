package com.portfolio.wallet.architecture.fixture.common;

import com.portfolio.wallet.architecture.fixture.transfer.application.TransferService;

/** Violation: common depends on a business module. */
public class SharedHelper {
  TransferService service;
}
