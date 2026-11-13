package com.portfolio.wallet.admin.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One item of the transfer review queue: what a compliance officer needs to choose the next
 * transfer (soonest deadline first). No names or wallet numbers; those are in the detail.
 *
 * @param score the risk score, staff-only
 */
public record TransferReviewSummary(
    UUID transferId,
    String reference,
    BigDecimal amount,
    String currency,
    Instant heldAt,
    Instant reviewExpiresAt,
    Integer score) {

  @Override
  public String toString() {
    return "TransferReviewSummary[transferId=" + transferId + "]";
  }
}
