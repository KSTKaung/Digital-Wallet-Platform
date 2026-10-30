package com.portfolio.wallet.transfer.domain;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The immutable facts of a transfer, fixed at submission (V4 {@code transfer.transfers}; the update
 * trigger forbids changing them).
 *
 * <p>Invariants: sender ≠ receiver, both as customer and as wallet (doc 05 §4); amount positive;
 * description at most {@value #MAX_DESCRIPTION_LENGTH} characters (doc 08 Q4); the business day the
 * daily limit is counted on is stored ({@code limitDate}, MVP-ADR-18, -22).
 *
 * @param id generated before the risk assessment (doc 08 Q8)
 * @param reference unique server-generated reference, also the ledger transaction reference
 * @param description customer note, optional; never logged (doc 08 Q4), so {@link #toString()}
 *     omits it
 * @param riskAssessmentId required: every transfer is assessed before it is stored (MVP-ADR-24;
 *     {@code risk_assessment_id} is {@code NOT NULL} in V4)
 */
public record TransferDetails(
    UUID id,
    String reference,
    UUID senderCustomerId,
    UUID receiverCustomerId,
    UUID senderWalletId,
    UUID receiverWalletId,
    Money amount,
    String description,
    UUID idempotencyKey,
    LocalDate limitDate,
    UUID riskAssessmentId) {

  /** Maximum description length in characters (doc 08 Q4; V4 {@code varchar(140)}). */
  public static final int MAX_DESCRIPTION_LENGTH = 140;

  /** Maximum reference length (V4 {@code reference varchar(40)}). */
  public static final int MAX_REFERENCE_LENGTH = 40;

  public TransferDetails {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(senderCustomerId, "senderCustomerId");
    Objects.requireNonNull(receiverCustomerId, "receiverCustomerId");
    Objects.requireNonNull(senderWalletId, "senderWalletId");
    Objects.requireNonNull(receiverWalletId, "receiverWalletId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(limitDate, "limitDate");
    Objects.requireNonNull(riskAssessmentId, "riskAssessmentId");
    if (reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH) {
      throw new IllegalArgumentException("reference must be 1 to 40 characters");
    }
    if (senderCustomerId.equals(receiverCustomerId) || senderWalletId.equals(receiverWalletId)) {
      throw new SelfTransferException("sender and receiver must differ");
    }
    if (!amount.isPositive()) {
      throw new InvalidAmountException("amount must be positive");
    }
    // PostgreSQL varchar counts characters (code points), not UTF-16 units.
    if (description != null
        && description.codePointCount(0, description.length()) > MAX_DESCRIPTION_LENGTH) {
      throw new InvalidTransferDetailsException("description is longer than 140 characters");
    }
  }

  /** Omits the description, which must never be logged (doc 08 Q4). */
  @Override
  public String toString() {
    return "TransferDetails[id="
        + id
        + ", reference="
        + reference
        + ", amount="
        + amount
        + ", limitDate="
        + limitDate
        + "]";
  }
}
