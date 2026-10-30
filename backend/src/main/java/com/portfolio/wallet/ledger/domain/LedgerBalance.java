package com.portfolio.wallet.ledger.domain;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Derived ledger balance: Σ CREDIT − Σ DEBIT (documentations/01 §6.2). For a customer account this
 * equals the wallet's available plus reserved balance (MVP-ADR-03 reconciliation). System accounts
 * may be negative: {@code SYS-CASH-CLEARING} goes negative as customers deposit.
 */
public final class LedgerBalance {

  private LedgerBalance() {}

  /**
   * Sums the given entries, which the caller has selected for one account.
   *
   * @param currency the account currency; also the result's currency when there are no entries
   * @throws CurrencyMismatchException if an entry is in another currency
   */
  public static Money derive(Currency currency, Iterable<LedgerEntry> entries) {
    Money.requireSupportedCurrency(currency);
    Objects.requireNonNull(entries, "entries");
    BigDecimal balance = BigDecimal.ZERO;
    for (LedgerEntry entry : entries) {
      if (!entry.amount().currency().equals(currency)) {
        throw new CurrencyMismatchException("ledger entry currency differs from the account");
      }
      BigDecimal amount = entry.amount().amount();
      balance = entry.type() == EntryType.CREDIT ? balance.add(amount) : balance.subtract(amount);
    }
    return Money.of(balance, currency);
  }
}
