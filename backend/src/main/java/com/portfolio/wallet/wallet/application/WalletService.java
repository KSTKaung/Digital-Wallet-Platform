package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.ledger.application.LedgerAccountService;
import com.portfolio.wallet.wallet.domain.Tier;
import com.portfolio.wallet.wallet.domain.Wallet;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wallets for other modules (documentations/02 §2.3; doc 06 MVP-ADR-10). Callers pass and receive
 * ids and plain values: the wallet's domain and persistence types stay inside this module.
 */
@Service
public class WalletService {

  private static final Logger log = LoggerFactory.getLogger(WalletService.class);

  private final WalletRepository wallets;
  private final LedgerAccountService ledgerAccounts;
  private final WalletNumberGenerator numbers;
  private final AuditService audit;
  private final WalletProperties properties;

  public WalletService(
      WalletRepository wallets,
      LedgerAccountService ledgerAccounts,
      WalletNumberGenerator numbers,
      AuditService audit,
      WalletProperties properties) {
    this.wallets = wallets;
    this.ledgerAccounts = ledgerAccounts;
    this.numbers = numbers;
    this.audit = audit;
    this.properties = properties;
  }

  /**
   * Opens the customer's wallet on KYC approval, inside the approval's transaction (MVP-ADR-10; doc
   * 07 §3): {@code ACTIVE}, THB, zero balances, the tier from the approved level ({@link
   * Tier#forKycLevel}), a random wallet number, and its {@code CUSTOMER} ledger account; audited in
   * the same transaction. Any failure rolls all of it back with the approval.
   *
   * <p>A wallet-number clash draws a new number, at most {@link
   * WalletProperties#walletNumberAttempts()} times in all. The insert takes no lock on existing
   * rows, so it adds nothing to the lock order (doc 07 §8).
   *
   * @param kycLevel the approved verification level, {@code BASIC} or {@code FULL}
   * @param reviewerId the staff member who approved, recorded as the audit actor
   * @throws CustomerAlreadyHasWalletException if the customer has a wallet ({@code
   *     uq_wallets_customer}); the transaction is then unusable and must roll back
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public OpenedWallet openForApprovedKyc(
      UUID customerId, String kycLevel, UUID reviewerId, InetAddress clientIp) {
    Tier tier = Tier.forKycLevel(kycLevel);
    int attempts = properties.walletNumberAttempts();
    for (int attempt = 1; attempt <= attempts; attempt++) {
      Wallet wallet = Wallet.open(UUID.randomUUID(), numbers.next(), customerId, tier, Money.THB);
      if (insert(wallet).isPresent()) {
        UUID ledgerAccountId = ledgerAccounts.openCustomerAccount(wallet.id());
        audit.record(WalletAudit.created(wallet, ledgerAccountId, reviewerId, clientIp));
        return new OpenedWallet(wallet.id(), ledgerAccountId);
      }
      // The number itself is a lookup key and is not logged.
      log.warn("Wallet number already in use; drawing another: attempt={}", attempt);
    }
    throw new IllegalStateException("no unused wallet number after " + attempts + " attempts");
  }

  /** Whether the customer has a wallet, in any status. */
  @Transactional(readOnly = true)
  public boolean existsForCustomer(UUID customerId) {
    return wallets.existsForCustomer(customerId);
  }

  private Optional<UUID> insert(Wallet wallet) {
    try {
      return wallets.insertIfNumberFree(
          wallet.id(),
          wallet.walletNumber().value(),
          wallet.customerId(),
          wallet.tier().name(),
          wallet.status().name(),
          wallet.currency().getCurrencyCode(),
          wallet.availableBalance().amount(),
          wallet.reservedBalance().amount());
    } catch (DuplicateKeyException e) {
      // Wallet-number clashes are absorbed by the insert; what is left is the customer.
      throw new CustomerAlreadyHasWalletException();
    }
  }
}
