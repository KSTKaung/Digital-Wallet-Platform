package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.admin.application.TransferReviewSummary;
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
