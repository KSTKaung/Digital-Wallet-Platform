package com.portfolio.wallet.architecture.fixture.wallet.application;

import com.portfolio.wallet.architecture.fixture.ledger.application.LedgerAccounts;

/** Fixture: wallet depending on ledger, which {@code moneyCoreDependsDownwardOnly} allows. */
public class WalletOpening {

  private final LedgerAccounts accounts = new LedgerAccounts();

  public String open() {
    return accounts.open();
  }
}
