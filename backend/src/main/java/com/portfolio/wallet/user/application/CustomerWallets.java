package com.portfolio.wallet.user.application;

import com.portfolio.wallet.user.domain.VerificationLevel;
import java.net.InetAddress;
import java.util.UUID;

/**
 * The user module's view of the customer's wallet: whether one exists, for {@code walletCreated} in
 * the KYC status (documentations/02 §2.2), and opening it on approval (doc 06 MVP-ADR-10). A seam
 * over {@code wallet.application}, implemented by {@link WalletModuleCustomerWallets}.
 */
public interface CustomerWallets {

  boolean exists(UUID customerId);

  /**
   * Opens the wallet and its ledger account inside the caller's transaction.
   *
   * @param reviewerId the approving staff member, the audit actor
   * @return the new wallet's id
   * @throws com.portfolio.wallet.common.error.ApiException {@code INVALID_KYC_STATE} if the
   *     customer has a wallet already; the transaction must then roll back
   */
  UUID open(UUID customerId, VerificationLevel level, UUID reviewerId, InetAddress clientIp);
}
