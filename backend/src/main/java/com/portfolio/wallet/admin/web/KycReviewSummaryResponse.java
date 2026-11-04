package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.user.application.KycReviewSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /admin/kyc/applications}: document reference and legal name masked
 * (documentations/06 MVP-ADR-10 amendment). {@link #toString()} leaves the KYC content out.
 */
record KycReviewSummaryResponse(
    UUID id,
    UUID customerId,
    String status,
    String verificationLevel,
    String documentType,
    String documentReference,
    String maskedName,
    Instant submittedAt,
    Instant decidedAt,
    Instant createdAt) {

  static KycReviewSummaryResponse from(KycReviewSummary summary) {
    return new KycReviewSummaryResponse(
        summary.id(),
        summary.customerId(),
        summary.status(),
        summary.verificationLevel(),
        summary.documentType(),
        summary.maskedDocumentReference(),
        summary.maskedName(),
        summary.submittedAt(),
        summary.decidedAt(),
        summary.createdAt());
  }

  @Override
  public String toString() {
    return "KycReviewSummaryResponse[id=" + id + ", status=" + status + "]";
  }
}
