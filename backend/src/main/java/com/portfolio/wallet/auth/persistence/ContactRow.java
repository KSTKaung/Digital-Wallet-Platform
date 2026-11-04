package com.portfolio.wallet.auth.persistence;

/** An account's email and phone. {@link #toString()} leaves both out (doc 03 §5.1). */
public record ContactRow(String email, String phoneNumber) {

  @Override
  public String toString() {
    return "ContactRow[]";
  }
}
