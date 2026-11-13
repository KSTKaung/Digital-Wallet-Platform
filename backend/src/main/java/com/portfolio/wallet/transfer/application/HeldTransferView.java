package com.portfolio.wallet.transfer.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A transfer that was held for review, as compliance officers see it (documentations/02 §2.7; doc
 * 06 MVP-ADR-24 amendment "Review queue (Q12)"): Internal, staff-only data. Never the customer's
 * description, which no staff endpoint is documented to show. {@link #toString()} keeps the id and
 * status only.
 *
 * @param riskAssessmentId the assessment whose score and rules the detail shows
 * @param rejectionReasonCategory the customer-facing category of a {@code REJECTED} transfer
 */
public record HeldTransferView(
    UUID id,
    String reference,
    String status,
    BigDecimal amount,
    String currency,
    UUID senderCustomerId,
    UUID receiverCustomerId,
    UUID senderWalletId,
    UUID receiverWalletId,
    UUID riskAssessmentId,
    Instant createdAt,
    Instant heldAt,
    Instant reviewExpiresAt,
    Instant decidedAt,
    String rejectionReasonCategory,
    String ledgerStatus) {

  public HeldTransferView {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(riskAssessmentId, "riskAssessmentId");
  }

  @Override
  public String toString() {
    return "HeldTransferView[id=" + id + ", status=" + status + "]";
  }
}
