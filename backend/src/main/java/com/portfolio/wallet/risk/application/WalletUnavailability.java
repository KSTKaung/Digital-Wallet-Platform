package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.risk.domain.UnavailableWallet;

/**
 * Which wallet an approval found frozen or closed, for a system rejection (documentations/06
 * MVP-ADR-24 amendment, D2 case 3), for callers outside the risk module. The caller checks the
 * sender's wallet first. The customer only ever sees {@code WALLET_UNAVAILABLE}, never which
 * wallet.
 */
public enum WalletUnavailability {
  SENDER_WALLET_FROZEN,
  SENDER_WALLET_CLOSED,
  RECIPIENT_WALLET_FROZEN,
  RECIPIENT_WALLET_CLOSED;

  UnavailableWallet toDomain() {
    return switch (this) {
      case SENDER_WALLET_FROZEN -> UnavailableWallet.SENDER_WALLET_FROZEN;
      case SENDER_WALLET_CLOSED -> UnavailableWallet.SENDER_WALLET_CLOSED;
      case RECIPIENT_WALLET_FROZEN -> UnavailableWallet.RECIPIENT_WALLET_FROZEN;
      case RECIPIENT_WALLET_CLOSED -> UnavailableWallet.RECIPIENT_WALLET_CLOSED;
    };
  }
}
