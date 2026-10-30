package com.portfolio.wallet.ledger.domain;

/**
 * Side of a ledger entry. For customer accounts CREDIT increases and DEBIT decreases the balance
 * (documentations/01 §6.2).
 */
public enum EntryType {
  DEBIT,
  CREDIT
}
