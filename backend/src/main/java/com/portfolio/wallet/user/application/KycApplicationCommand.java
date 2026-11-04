package com.portfolio.wallet.user.application;

/**
 * The fields of a create or edit request, as received; {@link KycApplicationService} turns them
 * into domain values. {@link #toString()} leaves the KYC content out.
 */
public record KycApplicationCommand(
    String verificationLevel,
    String documentType,
    String documentReference,
    String firstName,
    String lastName) {

  @Override
  public String toString() {
    return "KycApplicationCommand[]";
  }
}
