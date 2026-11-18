package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.risk.application.WalletUnavailability;
import com.portfolio.wallet.wallet.application.UnavailableWallet;
import java.util.Objects;

/**
 * Maps the wallet that made an approval release its hold (the wallet module's {@link
 * UnavailableWallet}) to the system reason the review row records (the risk module's {@link
 * WalletUnavailability}; documentations/06 MVP-ADR-24 amendment, D2 case 3). The two modules do not
 * depend on each other, so the transfer module, which calls both, maps them here; a test checks
 * every value name by name.
 */
final class UnavailableWallets {

  private UnavailableWallets() {}

  static WalletUnavailability toRisk(UnavailableWallet wallet) {
    Objects.requireNonNull(wallet, "wallet");
    return switch (wallet) {
      case SENDER_FROZEN -> WalletUnavailability.SENDER_WALLET_FROZEN;
      case SENDER_CLOSED -> WalletUnavailability.SENDER_WALLET_CLOSED;
      case RECIPIENT_FROZEN -> WalletUnavailability.RECIPIENT_WALLET_FROZEN;
      case RECIPIENT_CLOSED -> WalletUnavailability.RECIPIENT_WALLET_CLOSED;
    };
  }
}
