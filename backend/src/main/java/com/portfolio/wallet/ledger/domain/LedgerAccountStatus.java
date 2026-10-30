package com.portfolio.wallet.ledger.domain;

/**
 * Ledger account status (V2 {@code ck_ledger_accounts_status}). Only {@code ACTIVE} accepts
 * postings (documentations/01 §6.5 invariant 6).
 */
public enum LedgerAccountStatus {
  ACTIVE,
  FROZEN,
  CLOSED
}
