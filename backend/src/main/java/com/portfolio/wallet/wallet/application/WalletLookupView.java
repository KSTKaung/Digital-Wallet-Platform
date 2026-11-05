package com.portfolio.wallet.wallet.application;

/**
 * A lookup result (documentations/02 §2.3; doc 06 MVP-ADR-20): the recipient's masked legal name
 * and masked wallet number, nothing else.
 */
public record WalletLookupView(String maskedName, String maskedWalletNumber) {

  @Override
  public String toString() {
    return "WalletLookupView[]";
  }
}
