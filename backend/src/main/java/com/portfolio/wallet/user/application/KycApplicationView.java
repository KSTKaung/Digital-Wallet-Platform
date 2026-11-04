package com.portfolio.wallet.user.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A KYC application as its owner sees it: the document reference masked (documentations/06
 * MVP-ADR-10 amendment), the rejection reason shown (doc 08 §4B.2 Q13). {@link #toString()} leaves
 * the KYC content out.
 */
public record KycApplicationView(
    UUID id,
    String status,
    String verificationLevel,
    String documentType,
    String maskedDocumentReference,
    String firstName,
    String lastName,
    String rejectionReason,
    Instant submittedAt,
    Instant decidedAt,
    Instant createdAt,
    Instant updatedAt) {

  @Override
  public String toString() {
    return "KycApplicationView[id=" + id + ", status=" + status + "]";
  }
}
