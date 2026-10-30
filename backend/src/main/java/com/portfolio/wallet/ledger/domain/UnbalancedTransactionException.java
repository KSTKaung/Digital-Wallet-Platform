package com.portfolio.wallet.ledger.domain;

/**
 * A financial transaction has fewer than 2 entries, or its debits and credits differ
 * (documentations/01 §6.5 invariant 1). An internal error ({@code SYSTEM_ERROR}): postings come
 * only from {@link PostingTemplate}.
 */
public class UnbalancedTransactionException extends RuntimeException {

  public UnbalancedTransactionException(String message) {
    super(message);
  }
}
