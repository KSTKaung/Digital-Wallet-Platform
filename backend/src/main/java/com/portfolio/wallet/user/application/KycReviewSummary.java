package com.portfolio.wallet.user.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the staff list (documentations/02 §2.7): the document reference masked like everywhere
 * else and, because a list shows many customers at once, the legal name masked as well (first name
 * and last-name initial). {@link #toString()} leaves the KYC content out.
 */
public record KycReviewSummary(
    UUID id,
    UUID customerId,
    String status,
    String verificationLevel,
    String documentType,
    String maskedDocumentReference,
    String maskedName,
    Instant submittedAt,
    Instant decidedAt,
    Instant createdAt) {

  @Override
  public String toString() {
    return "KycReviewSummary[id=" + id + ", status=" + status + "]";
  }
}
