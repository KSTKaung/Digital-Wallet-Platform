package com.portfolio.wallet.ledger.domain;

/**
 * Financial transaction types in the MVP (documentations/01 §6.4; V2 {@code
 * ck_financial_transactions_type}).
 */
public enum TransactionType {
  DEPOSIT,
  WITHDRAWAL,
  TRANSFER
}
