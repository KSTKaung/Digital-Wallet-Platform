package com.portfolio.wallet.architecture.fixture.wallet.application;

import com.portfolio.wallet.architecture.fixture.user.application.CustomerFacade;

/** Fixture: wallet depending on user, which {@code moneyCoreDependsDownwardOnly} must catch. */
public class WalletOwnerReader {

  private final CustomerFacade customers = new CustomerFacade();

  public String owner() {
    return customers.legalName();
  }
}
