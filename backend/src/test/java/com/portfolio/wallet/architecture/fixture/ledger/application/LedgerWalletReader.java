package com.portfolio.wallet.architecture.fixture.ledger.application;

import com.portfolio.wallet.architecture.fixture.wallet.application.WalletOpening;

/** Fixture: ledger depending on wallet, which {@code moneyCoreDependsDownwardOnly} must catch. */
public class LedgerWalletReader {

  private final WalletOpening wallets = new WalletOpening();

  public String read() {
    return wallets.open();
  }
}
