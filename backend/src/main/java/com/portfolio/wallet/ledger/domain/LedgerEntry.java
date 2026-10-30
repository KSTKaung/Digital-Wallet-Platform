package com.portfolio.wallet.ledger.domain;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One side of a posting (V2 {@code ledger.ledger_entries}). The amount is always positive; the
 * direction is the {@link EntryType} (documentations/01 §6.5 invariant 2). Entries are immutable
 * and append-only (MVP-ADR-03).
 */
public record LedgerEntry(UUID accountId, EntryType type, Money amount) {

  public LedgerEntry {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new InvalidAmountException("ledger entry amount must be positive");
    }
  }

  public static LedgerEntry debit(UUID accountId, Money amount) {
    return new LedgerEntry(accountId, EntryType.DEBIT, amount);
  }

  public static LedgerEntry credit(UUID accountId, Money amount) {
    return new LedgerEntry(accountId, EntryType.CREDIT, amount);
  }
}
