package com.portfolio.wallet.ledger.domain;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.Money;
import java.util.List;
import java.util.Objects;

/**
 * The MVP posting templates (documentations/01 §6.4; doc 06 MVP-ADR-13):
 *
 * <table>
 *   <caption>Templates</caption>
 *   <tr><th>Type</th><th>DEBIT</th><th>CREDIT</th></tr>
 *   <tr><td>DEPOSIT</td><td>{@code SYS-CASH-CLEARING}</td><td>customer</td></tr>
 *   <tr><td>WITHDRAWAL</td><td>customer</td><td>{@code SYS-CASH-CLEARING}</td></tr>
 *   <tr><td>TRANSFER</td><td>sender</td><td>receiver</td></tr>
 * </table>
 *
 * Every account must be {@code ACTIVE} (doc 01 §6.5 invariant 6) and in the amount's currency.
 */
public final class PostingTemplate {

  private PostingTemplate() {}

  /** DR {@code SYS-CASH-CLEARING} / CR customer. */
  public static FinancialTransaction deposit(
      String reference, LedgerAccount customer, LedgerAccount cashClearing, Money amount) {
    requireCustomer(customer);
    requireCashClearing(cashClearing);
    return post(reference, TransactionType.DEPOSIT, cashClearing, customer, amount);
  }

  /** DR customer / CR {@code SYS-CASH-CLEARING}. */
  public static FinancialTransaction withdrawal(
      String reference, LedgerAccount customer, LedgerAccount cashClearing, Money amount) {
    requireCustomer(customer);
    requireCashClearing(cashClearing);
    return post(reference, TransactionType.WITHDRAWAL, customer, cashClearing, amount);
  }

  /** DR sender / CR receiver. */
  public static FinancialTransaction transfer(
      String reference, LedgerAccount sender, LedgerAccount receiver, Money amount) {
    requireCustomer(sender);
    requireCustomer(receiver);
    if (sender.id().equals(receiver.id())) {
      throw new InvalidPostingException("sender and receiver accounts must differ");
    }
    return post(reference, TransactionType.TRANSFER, sender, receiver, amount);
  }

  private static FinancialTransaction post(
      String reference,
      TransactionType type,
      LedgerAccount debited,
      LedgerAccount credited,
      Money amount) {
    Objects.requireNonNull(amount, "amount");
    requirePostable(debited, amount);
    requirePostable(credited, amount);
    return new FinancialTransaction(
        reference,
        type,
        List.of(
            LedgerEntry.debit(debited.id(), amount), LedgerEntry.credit(credited.id(), amount)));
  }

  private static void requireCustomer(LedgerAccount account) {
    Objects.requireNonNull(account, "account");
    if (account.type() != AccountType.CUSTOMER) {
      throw new InvalidPostingException("a customer ledger account is required");
    }
  }

  private static void requireCashClearing(LedgerAccount account) {
    Objects.requireNonNull(account, "cashClearing");
    if (!account.isCashClearing()) {
      throw new InvalidPostingException("the SYS-CASH-CLEARING account is required");
    }
  }

  private static void requirePostable(LedgerAccount account, Money amount) {
    if (!account.acceptsPostings()) {
      throw new LedgerAccountNotActiveException("ledger account does not accept postings");
    }
    if (!account.currency().equals(amount.currency())) {
      throw new CurrencyMismatchException("amount currency differs from the account currency");
    }
  }
}
