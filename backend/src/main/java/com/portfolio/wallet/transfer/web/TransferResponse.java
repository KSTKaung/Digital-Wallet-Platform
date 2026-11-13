package com.portfolio.wallet.transfer.web;

import com.portfolio.wallet.transfer.application.TransferView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code Transfer} of documentations/02 §2.4 as the MVP returns it, from {@code POST
 * /transfers}, {@code GET /transfers}, {@code GET /transfers/{id}} and the cancel:
 *
 * <pre>
 * {id, reference, status, direction, amount, currency,
 *  counterparty: {maskedName, maskedWalletNumber}, rejectionReason, reviewExpiresAt, createdAt,
 *  ledgerStatus}
 * </pre>
 *
 * Never the description, the risk score, a rule or the reviewer's reason (doc 08 §4C.4 criterion
 * 20); see {@link TransferView} for each field.
 */
record TransferResponse(
    UUID id,
    String reference,
    String status,
    String direction,
    BigDecimal amount,
    String currency,
    Counterparty counterparty,
    String rejectionReason,
    Instant reviewExpiresAt,
    Instant createdAt,
    String ledgerStatus) {

  /** The other party, masked (MVP-ADR-20; Q8). */
  record Counterparty(String maskedName, String maskedWalletNumber) {

    @Override
    public String toString() {
      return "Counterparty[]";
    }
  }

  static TransferResponse from(TransferView view) {
    return new TransferResponse(
        view.id(),
        view.reference(),
        view.status(),
        view.direction(),
        view.amount(),
        view.currency(),
        new Counterparty(
            view.counterparty().maskedName(), view.counterparty().maskedWalletNumber()),
        view.rejectionReason(),
        view.reviewExpiresAt(),
        view.createdAt(),
        view.ledgerStatus());
  }

  @Override
  public String toString() {
    return "TransferResponse[id=" + id + ", status=" + status + "]";
  }
}
