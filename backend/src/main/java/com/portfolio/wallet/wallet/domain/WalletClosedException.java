package com.portfolio.wallet.wallet.domain;

/**
 * The wallet is {@code CLOSED} and accepts no operation (documentations/05 §4). No documented error
 * code exists; {@code CLOSED} is not reachable in the MVP (doc 07 D21).
 */
public class WalletClosedException extends RuntimeException {

  public WalletClosedException(String message) {
    super(message);
  }
}
