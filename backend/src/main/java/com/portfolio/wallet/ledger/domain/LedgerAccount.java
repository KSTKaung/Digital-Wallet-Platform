package com.portfolio.wallet.ledger.domain;

import com.portfolio.wallet.common.money.Money;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A ledger account (documentations/01 §6.3; V2 {@code ledger.ledger_accounts}). It stores no
 * balance: balances are derived from the entries ({@link LedgerBalance}).
 *
 * <ul>
 *   <li>{@code CUSTOMER} accounts have an owner reference (the wallet id) and no code.
 *   <li>{@code SYSTEM} and {@code SUSPENSE} accounts have a code and no owner reference.
 *   <li>Only {@code ACTIVE} accounts accept postings (doc 01 §6.5 invariant 6).
 * </ul>
 */
public final class LedgerAccount {

  /** Seeded system account for simulated cash in and out (V2 seed; doc 01 §6.3, §6.4). */
  public static final String CASH_CLEARING_CODE = "SYS-CASH-CLEARING";

  /** Seeded suspense account (V2 seed; doc 01 §6.3). */
  public static final String SUSPENSE_CODE = "SYS-SUSPENSE";

  /** V2 {@code code varchar(50)}. */
  public static final int MAX_CODE_LENGTH = 50;

  private final UUID id;
  private final String code;
  private final AccountType type;
  private final UUID ownerReference;
  private final Currency currency;
  private final LedgerAccountStatus status;

  private LedgerAccount(
      UUID id,
      String code,
      AccountType type,
      UUID ownerReference,
      Currency currency,
      LedgerAccountStatus status) {
    this.id = Objects.requireNonNull(id, "id");
    this.type = Objects.requireNonNull(type, "type");
    this.status = Objects.requireNonNull(status, "status");
    Money.requireSupportedCurrency(currency);
    boolean customer = type == AccountType.CUSTOMER;
    if (customer != (ownerReference != null)) {
      throw new IllegalArgumentException("only customer accounts have an owner reference");
    }
    if (customer == (code != null)) {
      throw new IllegalArgumentException("only system and suspense accounts have a code");
    }
    if (code != null && (code.isBlank() || code.length() > MAX_CODE_LENGTH)) {
      throw new IllegalArgumentException("code must be 1 to 50 characters");
    }
    this.code = code;
    this.ownerReference = ownerReference;
    this.currency = currency;
  }

  /** A new customer account for a wallet, {@code ACTIVE} (doc 07 §4.1). */
  public static LedgerAccount customer(UUID id, UUID walletId, Currency currency) {
    Objects.requireNonNull(walletId, "walletId");
    return new LedgerAccount(
        id, null, AccountType.CUSTOMER, walletId, currency, LedgerAccountStatus.ACTIVE);
  }

  /** Rebuilds a stored account (used by the persistence mapper). */
  public static LedgerAccount restore(
      UUID id,
      String code,
      AccountType type,
      UUID ownerReference,
      Currency currency,
      LedgerAccountStatus status) {
    return new LedgerAccount(id, code, type, ownerReference, currency, status);
  }

  public boolean acceptsPostings() {
    return status == LedgerAccountStatus.ACTIVE;
  }

  public boolean isCashClearing() {
    return type == AccountType.SYSTEM && CASH_CLEARING_CODE.equals(code);
  }

  public UUID id() {
    return id;
  }

  public Optional<String> code() {
    return Optional.ofNullable(code);
  }

  public AccountType type() {
    return type;
  }

  /** The wallet id of a customer account. */
  public Optional<UUID> ownerReference() {
    return Optional.ofNullable(ownerReference);
  }

  public Currency currency() {
    return currency;
  }

  public LedgerAccountStatus status() {
    return status;
  }

  @Override
  public String toString() {
    return "LedgerAccount[id=" + id + ", type=" + type + ", status=" + status + "]";
  }
}
