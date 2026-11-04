package com.portfolio.wallet.wallet.application;

/**
 * The customer has a wallet already, so no second one is opened ({@code uq_wallets_customer}, one
 * wallet per customer, documentations/05 §3.2). The caller decides what it means for its request;
 * the transaction must roll back, because the failed insert has aborted it.
 */
public class CustomerAlreadyHasWalletException extends RuntimeException {

  public CustomerAlreadyHasWalletException() {
    super("the customer already has a wallet");
  }
}
