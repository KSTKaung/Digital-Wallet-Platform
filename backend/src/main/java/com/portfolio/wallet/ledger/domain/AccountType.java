package com.portfolio.wallet.ledger.domain;

/**
 * Ledger account types in the MVP (documentations/01 §6.3; V2 {@code
 * ck_ledger_accounts_account_type}). {@code MERCHANT} and {@code FEE} are out of MVP scope.
 */
public enum AccountType {
  CUSTOMER,
  SYSTEM,
  SUSPENSE
}
