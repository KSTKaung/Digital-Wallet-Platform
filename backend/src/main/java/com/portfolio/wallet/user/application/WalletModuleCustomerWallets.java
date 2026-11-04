package com.portfolio.wallet.user.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.user.domain.VerificationLevel;
import com.portfolio.wallet.wallet.application.CustomerAlreadyHasWalletException;
import com.portfolio.wallet.wallet.application.WalletService;
import java.net.InetAddress;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link CustomerWallets} over {@code wallet.application} (module direction user → wallet). */
@Component
class WalletModuleCustomerWallets implements CustomerWallets {

  static final String ALREADY_HAS_WALLET = "The customer already has a wallet";

  private final WalletService wallets;

  WalletModuleCustomerWallets(WalletService wallets) {
    this.wallets = wallets;
  }

  @Override
  public boolean exists(UUID customerId) {
    return wallets.existsForCustomer(customerId);
  }

  // uq_wallets_customer is the final guard of "one wallet per customer"; reaching it means the
  // application can no longer be approved, so it is the approval's 409, not a 500.
  @Override
  public UUID open(
      UUID customerId, VerificationLevel level, UUID reviewerId, InetAddress clientIp) {
    try {
      return wallets.openForApprovedKyc(customerId, level.name(), reviewerId, clientIp).walletId();
    } catch (CustomerAlreadyHasWalletException e) {
      throw new ApiException(ErrorCode.INVALID_KYC_STATE, ALREADY_HAS_WALLET);
    }
  }
}
