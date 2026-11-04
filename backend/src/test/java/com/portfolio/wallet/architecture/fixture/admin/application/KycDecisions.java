package com.portfolio.wallet.architecture.fixture.admin.application;

import com.portfolio.wallet.architecture.fixture.user.application.CustomerFacade;

/** Fixture: admin depending on user, which {@code onlyAdminDependsOnUser} allows. */
public class KycDecisions {

  private final CustomerFacade customers = new CustomerFacade();

  public String decide() {
    return customers.legalName();
  }
}
