package com.portfolio.wallet.ledger.domain;

import java.util.Objects;

/**
 * What one entry on a customer account means to its customer: the {@code type} of a {@code GET
 * /transactions} item (documentations/06 MVP-ADR-11 amendment; doc 07 D06). It follows from the
 * financial transaction's type and the entry's side on the customer account, under the postings of
 * doc 01 §6.4 ({@link PostingTemplate}): a deposit credits the customer, a withdrawal debits it,
 * and a transfer debits the sender and credits the recipient.
 */
public enum AccountMovement {
  DEPOSIT,
  WITHDRAWAL,
  TRANSFER_IN,
  TRANSFER_OUT;

  /**
   * The movement of a customer-account entry.
   *
   * @throws IllegalArgumentException for a combination no MVP posting produces (a debited deposit
   *     or a credited withdrawal); the ledger has no reversals in the MVP
   */
  public static AccountMovement of(TransactionType transaction, EntryType entry) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(entry, "entry");
    return switch (transaction) {
      case DEPOSIT -> only(entry, EntryType.CREDIT, DEPOSIT);
      case WITHDRAWAL -> only(entry, EntryType.DEBIT, WITHDRAWAL);
      case TRANSFER -> entry == EntryType.CREDIT ? TRANSFER_IN : TRANSFER_OUT;
    };
  }

  private static AccountMovement only(EntryType entry, EntryType expected, AccountMovement result) {
    if (entry != expected) {
      throw new IllegalArgumentException(
          "a " + result + " posts " + expected + " on the customer account, not " + entry);
    }
    return result;
  }
}
