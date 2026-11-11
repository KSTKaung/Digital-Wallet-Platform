package com.portfolio.wallet.transfer.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One {@code transfer.transfers} row as stored (V4, V11). The database sets {@code created_at},
 * {@code updated_at}, {@code held_at} and {@code decided_at} (V4 triggers), so they are only ever
 * read back, never written from here (doc 08 §5).
 *
 * @param description the customer's note; never logged and never returned to a customer (doc 08
 *     §4C.4 criterion 20), so {@link #toString()} leaves it out
 */
public record TransferRow(
    UUID id,
    String reference,
    UUID senderCustomerId,
    UUID receiverCustomerId,
    UUID senderWalletId,
    UUID receiverWalletId,
    BigDecimal amount,
    String currency,
    String description,
    String status,
    UUID idempotencyKey,
    LocalDate limitDate,
    UUID riskAssessmentId,
    Instant heldAt,
    Instant reviewExpiresAt,
    Instant decidedAt,
    String rejectionReasonCategory,
    String ledgerStatus,
    String correlationId,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  /** Ids and the status only: the description and amounts stay out of logs. */
  @Override
  public String toString() {
    return "TransferRow[id=" + id + ", status=" + status + ", version=" + version + "]";
  }
}
