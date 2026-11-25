package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.admin.application.TransferReviewSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One item of {@code GET /admin/transfer-reviews}; staff-only. */
record TransferReviewSummaryResponse(
    UUID transferId,
    String reference,
    BigDecimal amount,
    String currency,
    Instant heldAt,
    Instant reviewExpiresAt,
    @Schema(nullable = true, description = "The risk score, 0-100; null if no assessment is found")
        Integer score) {

  static TransferReviewSummaryResponse from(TransferReviewSummary summary) {
    return new TransferReviewSummaryResponse(
        summary.transferId(),
        summary.reference(),
        summary.amount(),
        summary.currency(),
        summary.heldAt(),
        summary.reviewExpiresAt(),
        summary.score());
  }

  @Override
  public String toString() {
    return "TransferReviewSummaryResponse[transferId=" + transferId + "]";
  }
}
