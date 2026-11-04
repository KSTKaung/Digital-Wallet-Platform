package com.portfolio.wallet.wallet.application;

import java.util.UUID;

/** What {@link WalletService#openForApprovedKyc} created: the wallet and its ledger account. */
public record OpenedWallet(UUID walletId, UUID ledgerAccountId) {}
