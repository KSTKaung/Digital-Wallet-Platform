package com.portfolio.wallet.auth.persistence;

/**
 * An account's email address and status, for the email outbox's send-time lookup. {@link
 * #toString()} leaves the address out (doc 03 §5.1).
 */
public record RecipientRow(String email, String status) {

  @Override
  public String toString() {
    return "RecipientRow[status=" + status + "]";
  }
}
