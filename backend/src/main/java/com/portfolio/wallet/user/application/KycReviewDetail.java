package com.portfolio.wallet.user.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One application as the reviewing compliance officer sees it (documentations/02 §2.7): the legal
 * name in full, which the reviewer has to check (Confidential, staff only, doc 03 §5.1), the date
 * of birth and country copied onto the application at submit (K4; never the current profile), and
 * the document reference masked, as it is for everyone (owner, 2026-11-03). {@link #toString()}
 * leaves the KYC and profile content out.
 */
public record KycReviewDetail(
    UUID id,
    UUID customerId,
    String status,
    String verificationLevel,
    String documentType,
    String maskedDocumentReference,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String countryCode,
    UUID reviewerId,
    String rejectionReason,
    Instant submittedAt,
    Instant decidedAt,
    Instant createdAt,
    Instant updatedAt) {

  @Override
  public String toString() {
    return "KycReviewDetail[id=" + id + ", status=" + status + "]";
  }
}
