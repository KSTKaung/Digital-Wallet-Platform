package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds the transfer module's audit entries (documentations/06 MVP-ADR-03 amendment, MVP-ADR-24
 * "Audit of risk decisions" and "Audit of review outcomes"; doc 07 §9.3).
 *
 * <ul>
 *   <li>{@value #TRANSFER}: the outcome of {@code POST /transfers}, one row per request that
 *       reached a business outcome: in the money transaction with the {@code COMPLETED} or {@code
 *       PENDING_REVIEW} row, in the refusal's own transaction with the {@code BLOCKED} row or a
 *       stored refusal, and independently for a failed risk assessment;
 *   <li>{@value #TRANSFER_REVIEW}: an outcome of a held transfer (5a: the customer cancel, the
 *       expiry a late cancel causes, and the expiry job with actor {@code SYSTEM}; 5b: the staff
 *       approval and rejection, the system rejection of an approval that found a wallet
 *       unavailable, and the expiry a late staff action causes, with actor {@code STAFF}), in the
 *       transaction of the outcome, with the five facts of the review row as separate fields.
 * </ul>
 *
 * Values are ids, states, amounts and codes; never the description, the risk score, rule codes or
 * weights (those stay in the {@code risk} schema), a name, a wallet number or a reviewer's reason
 * text (staff-only, in {@code risk.risk_reviews.reason}).
 */
final class TransferAudit {

  static final String TRANSFER = "TRANSFER";
  static final String TRANSFER_REVIEW = "TRANSFER_REVIEW";

  private static final String STATUS = "status";

  private TransferAudit() {}

  /** A transfer row was written: {@code COMPLETED} or {@code PENDING_REVIEW} (success). */
  static AuditEntry created(TransferRow row, UUID ledgerTransactionId, InetAddress clientIp) {
    Map<String, Object> value = rowValues(row);
    if (ledgerTransactionId != null) {
      value.put("ledgerTransactionId", ledgerTransactionId.toString());
    }
    if (row.reviewExpiresAt() != null) {
      value.put("reviewExpiresAt", row.reviewExpiresAt().toString());
    }
    return AuditEntry.of(ActorType.CUSTOMER, TRANSFER, TRANSFER, AuditResult.SUCCESS)
        .withActor(row.senderCustomerId())
        .withResourceId(row.id().toString())
        .withClientIp(clientIp)
        .withNewValue(value);
  }

  /** A risk {@code BLOCK}: the {@code BLOCKED} row is kept and {@code 403 RISK_BLOCKED} stored. */
  static AuditEntry blocked(TransferRow row, InetAddress clientIp) {
    Map<String, Object> value = rowValues(row);
    value.put("errorCode", ErrorCode.RISK_BLOCKED.name());
    return AuditEntry.of(ActorType.CUSTOMER, TRANSFER, TRANSFER, AuditResult.FAILURE)
        .withActor(row.senderCustomerId())
        .withResourceId(row.id().toString())
        .withClientIp(clientIp)
        .withNewValue(value);
  }

  /**
   * A refusal without a transfer row: a stored business refusal of the pre-check or of the money
   * transaction, or a failed risk assessment ({@code SYSTEM_ERROR}).
   *
   * @param transferId the id generated for the transfer, once the request reached the risk
   *     assessment (it names the assessment); null for a pre-check refusal
   */
  static AuditEntry refused(
      UUID senderId,
      UUID transferId,
      UUID senderWalletId,
      Money amount,
      ErrorCode errorCode,
      InetAddress clientIp) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("senderWalletId", senderWalletId.toString());
    value.put("amount", amount.amount().toPlainString());
    value.put("currency", amount.currency().getCurrencyCode());
    value.put("errorCode", errorCode.name());
    return AuditEntry.of(ActorType.CUSTOMER, TRANSFER, TRANSFER, AuditResult.FAILURE)
        .withActor(senderId)
        .withResourceId(transferId == null ? null : transferId.toString())
        .withClientIp(clientIp)
        .withNewValue(value);
  }

  /**
   * The facts of one review row as the audit row records them, each in a field of its own
   * (MVP-ADR-24 "Audit of review outcomes", D2): the actual outcome, the decision source, the
   * requested action (null only for the expiry job), and the staff rejection category or the system
   * reason (each null unless it applies). Never the reviewer's own text.
   */
  record ReviewFacts(
      String action,
      String decidedBy,
      String requestedAction,
      String reasonCategory,
      String systemReason) {

    ReviewFacts {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(decidedBy, "decidedBy");
      if (reasonCategory != null && systemReason != null) {
        throw new IllegalArgumentException(
            "a staff category and a system reason never go together");
      }
    }
  }

  /**
   * An outcome of a held transfer, with the facts of its review row (MVP-ADR-24 "Audit of review
   * outcomes"); the actor is the audit row's actor. The customer-facing category of a rejection is
   * recorded as well.
   *
   * @param ledgerTransactionId the posting of an approval that captured the hold; null otherwise
   */
  static AuditEntry reviewOutcome(
      TransferRow before,
      TransferRow after,
      UUID actorId,
      ActorType actorType,
      ReviewFacts facts,
      UUID ledgerTransactionId,
      InetAddress clientIp) {
    Objects.requireNonNull(facts, "facts");
    Map<String, Object> value = new LinkedHashMap<>();
    value.put(STATUS, after.status());
    value.put("action", facts.action());
    value.put("decidedBy", facts.decidedBy());
    if (facts.requestedAction() != null) {
      value.put("requestedAction", facts.requestedAction());
    }
    if (facts.reasonCategory() != null) {
      value.put("reasonCategory", facts.reasonCategory());
    }
    if (facts.systemReason() != null) {
      value.put("systemReason", facts.systemReason());
    }
    if (after.rejectionReasonCategory() != null) {
      value.put("rejectionReasonCategory", after.rejectionReasonCategory());
    }
    value.put("amount", after.amount().toPlainString());
    value.put("currency", after.currency());
    value.put("limitDate", after.limitDate().toString());
    if (ledgerTransactionId != null) {
      value.put("ledgerTransactionId", ledgerTransactionId.toString());
    }
    return AuditEntry.of(actorType, TRANSFER_REVIEW, TRANSFER, AuditResult.SUCCESS)
        .withActor(actorId)
        .withResourceId(after.id().toString())
        .withClientIp(clientIp)
        .withOldValue(Map.of(STATUS, before.status()))
        .withNewValue(value);
  }

  private static Map<String, Object> rowValues(TransferRow row) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put(STATUS, row.status());
    value.put("reference", row.reference());
    value.put("senderWalletId", row.senderWalletId().toString());
    value.put("receiverWalletId", row.receiverWalletId().toString());
    value.put("receiverCustomerId", row.receiverCustomerId().toString());
    value.put("amount", row.amount().toPlainString());
    value.put("currency", row.currency());
    value.put("limitDate", row.limitDate().toString());
    value.put("riskAssessmentId", row.riskAssessmentId().toString());
    return value;
  }
}
