package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.wallet.domain.LimitPolicy;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.OperationLimits;
import com.portfolio.wallet.wallet.domain.Tier;
import com.portfolio.wallet.wallet.domain.Wallet;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's own wallet reads (documentations/02 §2.3). The customer id is always the JWT
 * subject, so there is no other customer's wallet to ask for. A wallet exists only after KYC
 * approval; before it every read is {@code 404 WALLET_NOT_FOUND} (MVP-ADR-10). Each read goes to
 * the database: nothing is cached, so a balance is never stale.
 */
@Service
public class WalletQueryService {

  static final String NOT_FOUND = "Wallet not found";

  private final WalletRepository wallets;
  private final LimitPolicy limits;

  public WalletQueryService(WalletRepository wallets, LimitPolicy limits) {
    this.wallets = wallets;
    this.limits = limits;
  }

  @Transactional(readOnly = true)
  public WalletView wallet(UUID customerId) {
    WalletRow row = find(customerId);
    Wallet wallet = WalletRows.toDomain(row);
    return new WalletView(
        wallet.walletNumber().value(),
        wallet.status().name(),
        wallet.tier().name(),
        wallet.availableBalance().amount(),
        wallet.reservedBalance().amount(),
        wallet.currency().getCurrencyCode(),
        row.createdAt());
  }

  @Transactional(readOnly = true)
  public WalletBalanceView balance(UUID customerId) {
    Wallet wallet = WalletRows.toDomain(find(customerId));
    return new WalletBalanceView(
        wallet.availableBalance().amount(),
        wallet.reservedBalance().amount(),
        wallet.currency().getCurrencyCode());
  }

  @Transactional(readOnly = true)
  public WalletStatusView status(UUID customerId) {
    return new WalletStatusView(WalletRows.toDomain(find(customerId)).status().name());
  }

  /** The configured limits for the wallet's tier, read at call time (MVP-ADR-18). */
  @Transactional(readOnly = true)
  public WalletLimitsView limits(UUID customerId) {
    Tier tier = WalletRows.toDomain(find(customerId)).tier();
    OperationLimits transfer = limits.limitsFor(Operation.TRANSFER);
    OperationLimits withdrawal = limits.limitsFor(Operation.WITHDRAWAL);
    OperationLimits deposit = limits.limitsFor(Operation.DEPOSIT);
    return new WalletLimitsView(
        transfer.dailyLimit(tier).amount(),
        withdrawal.dailyLimit(tier).amount(),
        deposit.dailyLimit(tier).amount(),
        transfer.maximumPerTransaction().amount(),
        withdrawal.maximumPerTransaction().amount(),
        deposit.maximumPerTransaction().amount(),
        transfer.minimum().amount(),
        withdrawal.minimum().amount(),
        deposit.minimum().amount(),
        transfer.minimum().currency().getCurrencyCode());
  }

  private WalletRow find(UUID customerId) {
    return wallets
        .findByCustomer(customerId)
        .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, NOT_FOUND));
  }
}
