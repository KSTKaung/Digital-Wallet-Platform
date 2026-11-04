package com.portfolio.wallet.architecture.fixture.wallet.application;

import com.portfolio.wallet.architecture.fixture.wallet.persistence.WalletRepository;

/**
 * Fixture: an application API exposing persistence, which {@code applicationApisHidePersistence}
 * must catch.
 */
public class WalletLeak {

  private final WalletRepository repository = new WalletRepository();

  public WalletRepository repository() {
    return repository;
  }

  public void save(WalletRepository other) {}

  /** Not public, so not part of the API: allowed. */
  WalletRepository internal() {
    return repository;
  }
}
