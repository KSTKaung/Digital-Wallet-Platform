package com.portfolio.wallet.wallet.domain;

/** Wallet status (documentations/01 §7.4; V3 {@code ck_wallets_status}). */
public enum WalletStatus {
  ACTIVE,
  FROZEN,
  CLOSED
}
