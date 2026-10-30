package com.portfolio.wallet.ledger.domain;

/**
 * A posting touches a ledger account that is {@code FROZEN} or {@code CLOSED} (documentations/01
 * §6.5 invariant 6). The whole money movement rolls back (MVP-ADR-03).
 */
public class LedgerAccountNotActiveException extends RuntimeException {

  public LedgerAccountNotActiveException(String message) {
    super(message);
  }
}
