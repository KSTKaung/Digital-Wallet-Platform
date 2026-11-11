package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.transfer.domain.RejectionCategory;
import com.portfolio.wallet.transfer.domain.Transfer;
import com.portfolio.wallet.transfer.domain.TransferDetails;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.util.Currency;
import java.util.UUID;

/** Maps stored transfers to the domain and to the customer's view. */
final class TransferViews {

  static final String SENT = "SENT";
  static final String RECEIVED = "RECEIVED";

  private TransferViews() {}

  /** The domain transfer of a stored row; checks the row against the domain's invariants. */
  static Transfer toDomain(TransferRow row) {
    TransferDetails details =
        new TransferDetails(
            row.id(),
            row.reference(),
            row.senderCustomerId(),
            row.receiverCustomerId(),
            row.senderWalletId(),
            row.receiverWalletId(),
            Money.of(row.amount(), Currency.getInstance(row.currency())),
            row.description(),
            row.idempotencyKey(),
            row.limitDate(),
            row.riskAssessmentId());
    return Transfer.restore(
        details,
        TransferStatus.valueOf(row.status()),
        row.heldAt(),
        row.reviewExpiresAt(),
        row.decidedAt(),
        row.rejectionReasonCategory() == null
            ? null
            : RejectionCategory.valueOf(row.rejectionReasonCategory()),
        row.version());
  }

  /**
   * The view of {@code row} for {@code caller}, who must be its sender or, for a {@code COMPLETED}
   * transfer, its receiver: the repository's Q8 rule chose the row, and this refuses anything else
   * so that a read that skipped the rule fails instead of disclosing (doc 08 §4C.2 Q8).
   *
   * @param counterparty the other party, masked
   */
  static TransferView view(TransferRow row, UUID caller, CounterpartyView counterparty) {
    String direction;
    if (row.senderCustomerId().equals(caller)) {
      direction = SENT;
    } else if (row.receiverCustomerId().equals(caller)
        && TransferStatus.COMPLETED.name().equals(row.status())) {
      direction = RECEIVED;
    } else {
      throw new IllegalStateException("transfer is not visible to the caller");
    }
    return new TransferView(
        row.id(),
        row.reference(),
        row.status(),
        direction,
        row.amount().setScale(Money.SCALE),
        row.currency(),
        counterparty,
        row.rejectionReasonCategory(),
        TransferStatus.PENDING_REVIEW.name().equals(row.status()) ? row.reviewExpiresAt() : null,
        row.createdAt(),
        row.ledgerStatus());
  }

  /**
   * The {@code 201} body {@code POST /transfers} returned when {@code row} was created, rebuilt
   * from the row for a key whose idempotency record has expired or been purged (Q14 applied to
   * transfers, owner 2026-11-11), so a key gets the same answer before and after its record is
   * gone. The creation outcome is fixed by the row: a transfer created as {@code PENDING_REVIEW}
   * has a {@code held_at} (set only then, and immutable; V4), every other {@code 201} was {@code
   * COMPLETED}. A later review decision does not change the answer; the transfer's current state is
   * read through {@code GET /transfers/{id}}.
   *
   * @param counterparty the recipient, masked
   * @throws IllegalStateException if {@code sender} did not send it, or if it was created {@code
   *     BLOCKED} (whose answer is {@code 403 RISK_BLOCKED}, not a body)
   */
  static TransferView asCreated(TransferRow row, UUID sender, CounterpartyView counterparty) {
    if (!row.senderCustomerId().equals(sender)) {
      throw new IllegalStateException("only the sender has a creation response");
    }
    boolean held = row.heldAt() != null;
    if (!held && !TransferStatus.COMPLETED.name().equals(row.status())) {
      throw new IllegalStateException("a transfer created without a hold that is not COMPLETED");
    }
    return new TransferView(
        row.id(),
        row.reference(),
        held ? TransferStatus.PENDING_REVIEW.name() : TransferStatus.COMPLETED.name(),
        SENT,
        row.amount().setScale(Money.SCALE),
        row.currency(),
        counterparty,
        null,
        held ? row.reviewExpiresAt() : null,
        row.createdAt(),
        held ? null : row.ledgerStatus());
  }

  /** The wallet of the other party, from {@code caller}'s side. */
  static UUID counterpartyWallet(TransferRow row, UUID caller) {
    return row.senderCustomerId().equals(caller) ? row.receiverWalletId() : row.senderWalletId();
  }
}
