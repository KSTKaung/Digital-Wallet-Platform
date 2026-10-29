package com.portfolio.wallet.architecture.fixture.transfer.application;

import com.portfolio.wallet.architecture.fixture.wallet.persistence.WalletRepository;

/** Violation: uses another module's persistence. */
public class TransferService {
  WalletRepository repository;
}
