package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds this module's audit entries (documentations/06 MVP-ADR-24 "Audit of risk decisions", owner
 * 2026-11-09). A risk decision records the decision, the assessment id (the resource), the sender
 * as the actor and whether an investigation was opened; never the amount, the score, rule codes or
 * weights, which stay in the {@code risk} schema.
 */
final class RiskAudit {

  static final String RISK_ASSESSMENT = "RISK_ASSESSMENT";
  static final String RISK_DECISION = "RISK_DECISION";

  private RiskAudit() {}

  /**
   * A committed assessment of the sender's transfer.
   *
   * @param clientIp the client's address, as the money audits record it; null when none was passed
   */
  static AuditEntry decided(
      UUID senderId,
      InetAddress clientIp,
      UUID assessmentId,
      AssessmentDecision decision,
      boolean investigationOpened) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("decision", decision.name());
    value.put("investigationOpened", investigationOpened);
    return AuditEntry.of(ActorType.CUSTOMER, RISK_DECISION, RISK_ASSESSMENT, AuditResult.SUCCESS)
        .withActor(senderId)
        .withClientIp(clientIp)
        .withResourceId(assessmentId.toString())
        .withNewValue(value);
  }
}
