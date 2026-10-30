package com.portfolio.wallet.wallet.domain;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Wallet balance snapshot (documentations/01 §6.1; doc 07 §3). The ledger is the system of record;
 * this snapshot is updated in the same database transaction as the posting (MVP-ADR-03).
 *
 * <ul>
 *   <li>Only an {@code ACTIVE} wallet can credit, debit, hold or capture (doc 05 §4: frozen wallets
 *       cannot transact).
 *   <li>A hold can be released while the wallet is {@code FROZEN} or {@code CLOSED}, so a held
 *       transfer can always leave review (cancel, reject, expiry; MVP-ADR-24).
 *   <li>A {@code CLOSED} wallet does nothing else (doc 05 §4).
 *   <li>Available and reserved balances never go below zero; amounts must be positive.
 * </ul>
 *
 * <p>Status is checked before amounts and balances (doc 03 §3.3 order). {@link #version()} is the
 * value read from the database; the persistence layer uses it for the optimistic check (MVP-ADR-08)
 * and the domain never changes it. Not thread-safe: one instance per database transaction.
 */
public final class Wallet {

  private final UUID id;
  private final WalletNumber walletNumber;
  private final UUID customerId;
  private final Tier tier;
  private final long version;
  private WalletStatus status;
  private Money availableBalance;
  private Money reservedBalance;

  private Wallet(
      UUID id,
      WalletNumber walletNumber,
      UUID customerId,
      Tier tier,
      WalletStatus status,
      Money availableBalance,
      Money reservedBalance,
      long version) {
    this.id = Objects.requireNonNull(id, "id");
    this.walletNumber = Objects.requireNonNull(walletNumber, "walletNumber");
    this.customerId = Objects.requireNonNull(customerId, "customerId");
    this.tier = Objects.requireNonNull(tier, "tier");
    this.status = Objects.requireNonNull(status, "status");
    Objects.requireNonNull(availableBalance, "availableBalance");
    Objects.requireNonNull(reservedBalance, "reservedBalance");
    if (!availableBalance.currency().equals(reservedBalance.currency())) {
      throw new CurrencyMismatchException("wallet balances are in different currencies");
    }
    if (availableBalance.isNegative() || reservedBalance.isNegative()) {
      throw new IllegalArgumentException("wallet balances must not be negative");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    this.availableBalance = availableBalance;
    this.reservedBalance = reservedBalance;
    this.version = version;
  }

  /** A new wallet on KYC approval: {@code ACTIVE}, zero balances (doc 07 §3; MVP-ADR-10). */
  public static Wallet open(
      UUID id, WalletNumber walletNumber, UUID customerId, Tier tier, Currency currency) {
    Money zero = Money.zero(currency);
    return new Wallet(id, walletNumber, customerId, tier, WalletStatus.ACTIVE, zero, zero, 0);
  }

  /** Rebuilds a stored wallet (used by the persistence mapper). */
  public static Wallet restore(
      UUID id,
      WalletNumber walletNumber,
      UUID customerId,
      Tier tier,
      WalletStatus status,
      Money availableBalance,
      Money reservedBalance,
      long version) {
    return new Wallet(
        id, walletNumber, customerId, tier, status, availableBalance, reservedBalance, version);
  }

  /** Adds money to the available balance (deposit, incoming transfer). */
  public void credit(Money amount) {
    requireActive();
    requirePositive(amount);
    availableBalance = availableBalance.plus(amount);
  }

  /**
   * Removes money from the available balance (withdrawal, outgoing transfer).
   *
   * @throws InsufficientBalanceException if the available balance is lower than the amount
   */
  public void debit(Money amount) {
    requireActive();
    requirePositive(amount);
    requireAvailable(amount);
    availableBalance = availableBalance.minus(amount);
  }

  /**
   * Moves money from available to reserved for a transfer under review (MVP-ADR-24).
   *
   * @throws InsufficientBalanceException if the available balance is lower than the amount
   */
  public void hold(Money amount) {
    requireActive();
    requirePositive(amount);
    requireAvailable(amount);
    availableBalance = availableBalance.minus(amount);
    reservedBalance = reservedBalance.plus(amount);
  }

  /**
   * Moves held money back from reserved to available (reject, cancel, expiry). Allowed in every
   * status, including {@code FROZEN} and {@code CLOSED}, so a held transfer can always leave review
   * (MVP-ADR-24).
   *
   * @throws InsufficientReservedBalanceException if less than the amount is reserved
   */
  public void releaseHold(Money amount) {
    requirePositive(amount);
    requireReserved(amount);
    reservedBalance = reservedBalance.minus(amount);
    availableBalance = availableBalance.plus(amount);
  }

  /**
   * Takes held money out of the wallet when a reviewed transfer is approved (MVP-ADR-24).
   *
   * @throws InsufficientReservedBalanceException if less than the amount is reserved
   */
  public void captureHold(Money amount) {
    requireActive();
    requirePositive(amount);
    requireReserved(amount);
    reservedBalance = reservedBalance.minus(amount);
  }

  /** {@code ACTIVE} → {@code FROZEN} (doc 05 §3.2: customer self-service freeze). */
  public void freeze() {
    if (status != WalletStatus.ACTIVE) {
      throw new InvalidWalletStatusTransitionException("only an active wallet can be frozen");
    }
    status = WalletStatus.FROZEN;
  }

  /** {@code FROZEN} → {@code ACTIVE} (doc 05 §3.2: staff unfreeze). */
  public void unfreeze() {
    if (status != WalletStatus.FROZEN) {
      throw new InvalidWalletStatusTransitionException("only a frozen wallet can be unfrozen");
    }
    status = WalletStatus.ACTIVE;
  }

  /** Available plus reserved: what the ledger-derived balance must equal (MVP-ADR-03). */
  public Money totalBalance() {
    return availableBalance.plus(reservedBalance);
  }

  public UUID id() {
    return id;
  }

  public WalletNumber walletNumber() {
    return walletNumber;
  }

  public UUID customerId() {
    return customerId;
  }

  public Tier tier() {
    return tier;
  }

  public WalletStatus status() {
    return status;
  }

  public Currency currency() {
    return availableBalance.currency();
  }

  public Money availableBalance() {
    return availableBalance;
  }

  public Money reservedBalance() {
    return reservedBalance;
  }

  public long version() {
    return version;
  }

  private void requireActive() {
    requireNotClosed();
    if (status == WalletStatus.FROZEN) {
      throw new WalletFrozenException("wallet is frozen");
    }
  }

  private void requireNotClosed() {
    if (status == WalletStatus.CLOSED) {
      throw new WalletClosedException("wallet is closed");
    }
  }

  private void requirePositive(Money amount) {
    Objects.requireNonNull(amount, "amount");
    if (!amount.currency().equals(currency())) {
      throw new CurrencyMismatchException("amount currency differs from the wallet currency");
    }
    if (!amount.isPositive()) {
      throw new InvalidAmountException("amount must be positive");
    }
  }

  private void requireAvailable(Money amount) {
    if (availableBalance.isLessThan(amount)) {
      throw new InsufficientBalanceException("available balance is insufficient");
    }
  }

  private void requireReserved(Money amount) {
    if (reservedBalance.isLessThan(amount)) {
      throw new InsufficientReservedBalanceException("reserved balance is insufficient");
    }
  }

  @Override
  public String toString() {
    return "Wallet[id=" + id + ", status=" + status + ", tier=" + tier + "]";
  }
}
