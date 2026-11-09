package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.common.money.Money;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The facts the {@code transfer} module passes for one assessment (documentations/06 MVP-ADR-24:
 * amount, sender wallet age, whether a completed transfer to this recipient exists). The frequency
 * count is the risk module's own.
 *
 * @param transferId the id the caller generated for the transfer before the assessment; one
 *     assessment per transfer ({@code uq_risk_assessments_transfer})
 * @param senderCustomerId the sender (auth user id); the per-sender lock and the frequency count
 *     are keyed by it
 * @param amount the transfer amount; positive
 * @param senderWalletCreatedAt the sender wallet's {@code created_at}, which is database time (V3);
 *     the age is measured against the risk transaction's database {@code now()}
 * @param completedTransferToRecipientExists whether a {@code COMPLETED} transfer from this sender
 *     to this recipient exists
 * @param clientIp the client's address for the decision's audit row, as the money audits record it;
 *     null when the caller has none
 */
public record RiskAssessmentRequest(
    UUID transferId,
    UUID senderCustomerId,
    Money amount,
    Instant senderWalletCreatedAt,
    boolean completedTransferToRecipientExists,
    InetAddress clientIp) {

  /** A request without a client address. */
  public RiskAssessmentRequest(
      UUID transferId,
      UUID senderCustomerId,
      Money amount,
      Instant senderWalletCreatedAt,
      boolean completedTransferToRecipientExists) {
    this(
        transferId,
        senderCustomerId,
        amount,
        senderWalletCreatedAt,
        completedTransferToRecipientExists,
        null);
  }

  public RiskAssessmentRequest {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(senderCustomerId, "senderCustomerId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(senderWalletCreatedAt, "senderWalletCreatedAt");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("amount must be positive");
    }
  }

  /** Leaves every value out: ids and amounts are never logged. */
  @Override
  public String toString() {
    return "RiskAssessmentRequest[…]";
  }
}
