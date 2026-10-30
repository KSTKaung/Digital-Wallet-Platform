package com.portfolio.wallet.wallet.domain;

/**
 * The wallet is {@code FROZEN} and cannot transact (documentations/05 §4). Maps to {@code
 * WALLET_FROZEN} (doc 02 §1.7).
 */
public class WalletFrozenException extends RuntimeException {

  public WalletFrozenException(String message) {
    super(message);
  }
}
