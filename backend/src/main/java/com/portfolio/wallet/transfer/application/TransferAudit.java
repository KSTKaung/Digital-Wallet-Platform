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
 *       expiry a late cancel causes, and the expiry job with actor {@code SYSTEM}; 5b adds the
 *       staff decisions), in the transaction of the outcome, with the five facts of the review row
 *       as separate fields.
 * </ul>
 *
 * Values are ids, states, amounts and codes; never the description, the risk score, rule codes or
 * weights (those stay in the {@code risk} schema), a name or a wallet number.
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
   * An outcome of a held transfer, with the facts of its review row (MVP-ADR-24 "Audit of review
   * outcomes"): the actual outcome, the decision source, the requested action and the system
   * reason; the actor is the audit row's actor.
   *
   * @param systemReason null unless the system decided
   */
  static AuditEntry reviewOutcome(
      TransferRow before,
      TransferRow after,
      UUID actorId,
      ActorType actorType,
      String action,
      String decidedBy,
      String requestedAction,
      String systemReason,
      InetAddress clientIp) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put(STATUS, after.status());
    value.put("action", action);
    value.put("decidedBy", decidedBy);
    if (requestedAction != null) {
      value.put("requestedAction", requestedAction);
    }
    if (systemReason != null) {
      value.put("systemReason", systemReason);
    }
    value.put("amount", after.amount().toPlainString());
    value.put("currency", after.currency());
    value.put("limitDate", after.limitDate().toString());
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
