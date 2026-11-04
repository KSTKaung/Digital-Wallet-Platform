package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.application.KycApplicationView;
import java.time.Instant;
import java.util.UUID;

/**
 * A KYC application for its owner: the document reference masked, the rejection reason shown
 * (documentations/02 §2.2). {@link #toString()} leaves the KYC content out.
 */
record KycApplicationResponse(
    UUID id,
    String status,
    String verificationLevel,
    String documentType,
    String documentReference,
    String firstName,
    String lastName,
    String rejectionReason,
    Instant submittedAt,
    Instant decidedAt,
    Instant createdAt,
    Instant updatedAt) {

  static KycApplicationResponse from(KycApplicationView view) {
    return new KycApplicationResponse(
        view.id(),
        view.status(),
        view.verificationLevel(),
        view.documentType(),
        view.maskedDocumentReference(),
        view.firstName(),
        view.lastName(),
        view.rejectionReason(),
        view.submittedAt(),
        view.decidedAt(),
        view.createdAt(),
        view.updatedAt());
  }

  @Override
  public String toString() {
    return "KycApplicationResponse[id=" + id + ", status=" + status + "]";
  }
}
