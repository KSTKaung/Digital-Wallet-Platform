package com.portfolio.wallet.architecture.fixture.risk.application;

import com.portfolio.wallet.architecture.fixture.admin.application.KycDecisions;
import com.portfolio.wallet.architecture.fixture.auth.application.ProfileReader;
import com.portfolio.wallet.architecture.fixture.transfer.application.TransferService;
import com.portfolio.wallet.architecture.fixture.user.application.CustomerFacade;
import com.portfolio.wallet.architecture.fixture.wallet.application.WalletOpening;

/**
 * Fixture: risk depending on transfer, wallet, admin, auth and user, each of which {@code
 * moneyCoreDependsDownwardOnly} must catch (documentations/06 MVP-ADR-24 "Dependencies": risk never
 * calls transfer; the caller passes the facts).
 */
public class RiskCallerReader {

  TransferService transfers;
  WalletOpening wallets;
  KycDecisions admin;
  ProfileReader auth;
  CustomerFacade users;
}
