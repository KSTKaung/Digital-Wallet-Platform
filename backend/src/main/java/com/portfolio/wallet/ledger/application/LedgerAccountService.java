package com.portfolio.wallet.ledger.application;

import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.ledger.domain.AccountType;
import com.portfolio.wallet.ledger.domain.LedgerAccount;
import com.portfolio.wallet.ledger.domain.LedgerAccountStatus;
import com.portfolio.wallet.ledger.persistence.LedgerAccountRepository;
import com.portfolio.wallet.ledger.persistence.LedgerAccountRow;
import java.util.Currency;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ledger accounts for other modules (documentations/01 §6.3; doc 07 §4.1). The persistence types
 * stay inside the ledger module: callers pass and receive ids only.
 */
@Service
public class LedgerAccountService {

  private final LedgerAccountRepository accounts;

  public LedgerAccountService(LedgerAccountRepository accounts) {
    this.accounts = accounts;
  }

  /**
   * Creates the {@code ACTIVE} THB {@code CUSTOMER} account of a new wallet, whose owner reference
   * is the wallet id (doc 07 §4.1). It runs only inside the caller's transaction, the KYC approval
   * that creates the wallet (MVP-ADR-10), so a failure anywhere leaves neither behind.
   *
   * @return the new account's id
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID openCustomerAccount(UUID walletId) {
    LedgerAccount account = LedgerAccount.customer(UUID.randomUUID(), walletId, Money.THB);
    accounts.insertCustomerAccount(
        account.id(), walletId, account.currency().getCurrencyCode(), account.status().name());
    return account.id();
  }

  /**
   * Freezes a wallet's {@code CUSTOMER} account, so it refuses new entries (V2 {@code
   * check_entry_insert}; doc 01 §6.5 invariant 6). Only inside the caller's transaction, which has
   * locked the wallet row first: the wallet and its account change status together (MVP-ADR-10
   * amendment), in the same lock order as a posting, wallet then account (doc 07 §8).
   *
   * @throws com.portfolio.wallet.ledger.domain.InvalidLedgerAccountStatusTransitionException if the
   *     account is not {@code ACTIVE}, which means it has drifted from its wallet
   * @throws IllegalStateException if the wallet has no customer account
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void freezeCustomerAccount(UUID walletId) {
    changeStatus(walletId, LedgerAccount::freeze);
  }

  /**
   * {@code FROZEN} → {@code ACTIVE}; otherwise as {@link #freezeCustomerAccount}.
   *
   * @throws com.portfolio.wallet.ledger.domain.InvalidLedgerAccountStatusTransitionException if the
   *     account is not {@code FROZEN}
   * @throws IllegalStateException if the wallet has no customer account
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void unfreezeCustomerAccount(UUID walletId) {
    changeStatus(walletId, LedgerAccount::unfreeze);
  }

  private void changeStatus(UUID walletId, UnaryOperator<LedgerAccount> transition) {
    LedgerAccountRow row =
        accounts
            .findCustomerAccountForUpdate(walletId)
            .orElseThrow(() -> new IllegalStateException("wallet without a ledger account"));
    LedgerAccount current = toDomain(row);
    LedgerAccount changed = transition.apply(current);
    // Under the row lock the status cannot have moved; 0 rows would mean a path skipped the lock.
    if (accounts.updateStatus(row.id(), current.status().name(), changed.status().name()) != 1) {
      throw new IllegalStateException("ledger account status changed under its row lock");
    }
  }

  private static LedgerAccount toDomain(LedgerAccountRow row) {
    return LedgerAccount.restore(
        row.id(),
        row.code(),
        AccountType.valueOf(row.accountType()),
        row.ownerReference(),
        Currency.getInstance(row.currency()),
        LedgerAccountStatus.valueOf(row.status()));
  }
}
