package com.portfolio.wallet.architecture.fixture.auth.application;

import com.portfolio.wallet.architecture.fixture.user.application.CustomerFacade;

/** Fixture: auth depending on user, which {@code onlyAdminDependsOnUser} must catch. */
public class ProfileReader {

  private final CustomerFacade customers = new CustomerFacade();

  public String read() {
    return customers.legalName();
  }
}
