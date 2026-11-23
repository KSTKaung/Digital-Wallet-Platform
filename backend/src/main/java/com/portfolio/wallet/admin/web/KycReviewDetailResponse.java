package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.user.application.KycReviewDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code GET /admin/kyc/applications/{id}} and the decision responses: legal name in full for the
 * reviewer, document reference masked (documentations/06 MVP-ADR-10 amendment), and the date of
 * birth and country as copied onto the application at submit (K4). {@link #toString()} leaves the
 * KYC and profile content out.
 */
record KycReviewDetailResponse(
    UUID id,
    UUID customerId,
    String status,
    String verificationLevel,
    String documentType,
    String documentReference,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String countryCode,
    @Schema(nullable = true, description = "The deciding officer; null until decided")
        UUID reviewerId,
    @Schema(nullable = true, description = "Null unless REJECTED") String rejectionReason,
    Instant submittedAt,
    @Schema(nullable = true, description = "Null until decided") Instant decidedAt,
    Instant createdAt,
    Instant updatedAt) {

  static KycReviewDetailResponse from(KycReviewDetail detail) {
    return new KycReviewDetailResponse(
        detail.id(),
        detail.customerId(),
        detail.status(),
        detail.verificationLevel(),
        detail.documentType(),
        detail.maskedDocumentReference(),
        detail.firstName(),
        detail.lastName(),
        detail.dateOfBirth(),
        detail.countryCode(),
        detail.reviewerId(),
        detail.rejectionReason(),
        detail.submittedAt(),
        detail.decidedAt(),
        detail.createdAt(),
        detail.updatedAt());
  }

  @Override
  public String toString() {
    return "KycReviewDetailResponse[id=" + id + ", status=" + status + "]";
  }
}
