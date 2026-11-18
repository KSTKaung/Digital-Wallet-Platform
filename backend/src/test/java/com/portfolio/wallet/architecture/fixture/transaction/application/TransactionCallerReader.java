package com.portfolio.wallet.architecture.fixture.transaction.application;

import com.portfolio.wallet.architecture.fixture.transfer.application.TransferService;
import com.portfolio.wallet.architecture.fixture.wallet.domain.Wallet;

/**
 * Fixture: the history module reaching past wallet and ledger, into {@code transfer.application}
 * and into the wallet module's domain; {@code transactionReadsOnlyWalletAndLedger} must catch both.
 */
public class TransactionCallerReader {

  TransferService transfers;
  Wallet wallet;
}
