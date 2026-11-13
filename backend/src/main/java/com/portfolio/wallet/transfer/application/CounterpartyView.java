package com.portfolio.wallet.transfer.application;

/**
 * The other party of a transfer as its customer sees it (documentations/06 MVP-ADR-24 amendment
 * "Visibility (Q8)", MVP-ADR-20): the masked legal name and the masked wallet number, nothing else.
 *
 * @param maskedName the first name plus the last name's initial, for example {@code Somchai J.}
 * @param maskedWalletNumber only the last 4 digits visible, for example {@code *******8901}
 */
public record CounterpartyView(String maskedName, String maskedWalletNumber) {

  @Override
  public String toString() {
    return "CounterpartyView[]";
  }
}
