package com.portfolio.wallet.transfer.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer as its customer sees it: the body of {@code POST /transfers} (also stored on the
 * idempotency record and replayed), of {@code GET /transfers/{id}}, of each item of {@code GET
 * /transfers} and of a cancel (documentations/02 §2.4; doc 06 MVP-ADR-03, -20, -24; doc 08 §4C.2
 * Q8, Q10, §4C.4 criteria 13, 20).
 *
 * <p>Never the description, the risk score, a rule, the reviewer's reason or any internal id other
 * than the transfer's own.
 *
 * @param status {@code COMPLETED}, {@code PENDING_REVIEW}, {@code REJECTED}, {@code CANCELLED},
 *     {@code EXPIRED} or {@code BLOCKED}; a receiver only ever sees {@code COMPLETED} (Q8)
 * @param direction {@code SENT} for the sender, {@code RECEIVED} for the receiver
 * @param amount scale 2 (MVP-ADR-21)
 * @param counterparty the other party, masked (Q8)
 * @param rejectionReason the customer category of a {@code REJECTED} transfer ({@code
 *     REVIEW_DECLINED} or {@code WALLET_UNAVAILABLE}, Q10); null otherwise
 * @param reviewExpiresAt the review deadline of a held transfer; null for every other status
 * @param createdAt when the transfer was submitted (database time)
 * @param ledgerStatus {@code POSTED} for {@code COMPLETED}, which is only ever returned after the
 *     commit that posted it (MVP-ADR-03); null for every other status
 */
public record TransferView(
    UUID id,
    String reference,
    String status,
    String direction,
    BigDecimal amount,
    String currency,
    CounterpartyView counterparty,
    String rejectionReason,
    Instant reviewExpiresAt,
    Instant createdAt,
    String ledgerStatus) {

  /** The transfer's own id and status only. */
  @Override
  public String toString() {
    return "TransferView[id=" + id + ", status=" + status + "]";
  }
}
