package com.portfolio.wallet.user.domain;

import java.util.Objects;

/** What the customer enters in an application; {@link #toString()} hides the KYC content. */
public record KycDetails(
    VerificationLevel verificationLevel,
    DocumentType documentType,
    DocumentReference documentReference,
    LegalName legalName) {

  public KycDetails {
    Objects.requireNonNull(verificationLevel, "verificationLevel");
    Objects.requireNonNull(documentType, "documentType");
    Objects.requireNonNull(documentReference, "documentReference");
    Objects.requireNonNull(legalName, "legalName");
  }

  @Override
  public String toString() {
    return "KycDetails[verificationLevel="
        + verificationLevel
        + ", documentType="
        + documentType
        + "]";
  }
}
