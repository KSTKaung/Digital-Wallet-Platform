package com.portfolio.wallet.wallet.application;

import java.util.Objects;

/**
 * What {@link WalletTransferService#approve} did with a held transfer (MVP-ADR-24 "Approve"; review
 * cases 1 and 3).
 */
public sealed interface ApprovalOutcome {

  /** Both wallets were {@code ACTIVE}: the hold was captured and the ledger posted (case 1). */
  record Captured(TransferSettlement settlement) implements ApprovalOutcome {

    public Captured {
      Objects.requireNonNull(settlement, "settlement");
    }
  }

  /**
   * A wallet was frozen or closed: the hold was released and nothing was posted (case 3). The
   * transfer becomes {@code REJECTED} with {@code WALLET_UNAVAILABLE}; {@code wallet} gives the
   * system reason.
   */
  record ReleasedWalletUnavailable(UnavailableWallet wallet) implements ApprovalOutcome {

    public ReleasedWalletUnavailable {
      Objects.requireNonNull(wallet, "wallet");
    }
  }
}
