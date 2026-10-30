package com.portfolio.wallet.ledger.domain;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * A balanced double-entry posting (documentations/01 §6.5; V2 {@code
 * ledger.financial_transactions}). Enforced on construction:
 *
 * <ol>
 *   <li>at least 2 entries;
 *   <li>every amount positive ({@link LedgerEntry});
 *   <li>one currency for all entries;
 *   <li>Σ DEBIT = Σ CREDIT.
 * </ol>
 *
 * The database checks the same rules again at commit (V2 deferred constraint triggers).
 *
 * @see PostingTemplate
 */
public final class FinancialTransaction {

  /** V2 {@code transaction_reference varchar(100)}. */
  public static final int MAX_REFERENCE_LENGTH = 100;

  /** Minimum number of entries (doc 01 §6.5 invariant 1). */
  public static final int MIN_ENTRIES = 2;

  private final String reference;
  private final TransactionType type;
  private final Currency currency;
  private final List<LedgerEntry> entries;
  private final Money total;

  /**
   * @param reference the business record's unique reference (doc 07 §4.2 invariant 4)
   * @throws UnbalancedTransactionException with fewer than 2 entries or Σ DEBIT ≠ Σ CREDIT
   * @throws CurrencyMismatchException if the entries use more than one currency
   */
  public FinancialTransaction(String reference, TransactionType type, List<LedgerEntry> entries) {
    Objects.requireNonNull(reference, "reference");
    this.type = Objects.requireNonNull(type, "type");
    this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    if (reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH) {
      throw new IllegalArgumentException("reference must be 1 to 100 characters");
    }
    if (this.entries.size() < MIN_ENTRIES) {
      throw new UnbalancedTransactionException("a ledger transaction needs at least 2 entries");
    }
    Currency first = this.entries.get(0).amount().currency();
    BigDecimal debits = BigDecimal.ZERO;
    BigDecimal credits = BigDecimal.ZERO;
    for (LedgerEntry entry : this.entries) {
      if (!entry.amount().currency().equals(first)) {
        throw new CurrencyMismatchException("ledger entries are in different currencies");
      }
      if (entry.type() == EntryType.DEBIT) {
        debits = debits.add(entry.amount().amount());
      } else {
        credits = credits.add(entry.amount().amount());
      }
    }
    if (debits.compareTo(credits) != 0) {
      throw new UnbalancedTransactionException("debit total differs from credit total");
    }
    this.reference = reference;
    this.currency = first;
    this.total = Money.of(debits, first);
  }

  public String reference() {
    return reference;
  }

  public TransactionType type() {
    return type;
  }

  public Currency currency() {
    return currency;
  }

  /** Unmodifiable, in posting order. */
  public List<LedgerEntry> entries() {
    return entries;
  }

  /** Σ DEBIT, which equals Σ CREDIT. */
  public Money total() {
    return total;
  }

  @Override
  public String toString() {
    return "FinancialTransaction[reference="
        + reference
        + ", type="
        + type
        + ", entries="
        + entries.size()
        + "]";
  }
}
