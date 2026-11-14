package com.portfolio.wallet.architecture.fixture.transaction.application;

import com.portfolio.wallet.architecture.fixture.common.SharedHelper;
import com.portfolio.wallet.architecture.fixture.ledger.application.LedgerAccounts;
import com.portfolio.wallet.architecture.fixture.wallet.application.WalletOpening;
import java.util.List;

/**
 * Fixture: what the history module may use: common, wallet.application, ledger.application, its own
 * package and the JDK. {@code transactionReadsOnlyWalletAndLedger} must accept it.
 */
public class TransactionHistoryReader {

  SharedHelper shared;
  WalletOpening wallets;
  LedgerAccounts ledger;
  TransactionCallerReader sibling;
  List<String> items;
}
