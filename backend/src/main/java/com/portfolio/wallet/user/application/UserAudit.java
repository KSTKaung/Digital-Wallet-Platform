package com.portfolio.wallet.user.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;

/**
 * Builds this module's audit entries. Values are states only: never the document reference, the
 * names, the date of birth or any other KYC or profile content (doc 07 §9.3); {@link AuditEntry}
 * refuses keys that suggest them.
 */
final class UserAudit {

  static final String CUSTOMER = "CUSTOMER";
  static final String KYC_APPLICATION = "KYC_APPLICATION";

  static final String PROFILE_UPDATE = "CUSTOMER_PROFILE_UPDATE";
  static final String KYC_CREATE = "KYC_APPLICATION_CREATE";
  static final String KYC_UPDATE = "KYC_APPLICATION_UPDATE";
  static final String KYC_SUBMIT = "KYC_APPLICATION_SUBMIT";
  static final String KYC_APPROVE = "KYC_APPLICATION_APPROVE";
  static final String KYC_REJECT = "KYC_APPLICATION_REJECT";

  private static final String STATUS = "status";

  private UserAudit() {}

  /**
   * A staff decision on an application: the states and the customer only. The rejection reason is
   * not recorded, because it is free text that could hold identity details (doc 08 §4B.2 Q13).
   */
  static AuditEntry decision(
      String action,
      UUID reviewerId,
      UUID applicationId,
      UUID customerId,
      String newStatus,
      InetAddress clientIp) {
    return AuditEntry.of(ActorType.STAFF, action, KYC_APPLICATION, AuditResult.SUCCESS)
        .withActor(reviewerId)
        .withResourceId(applicationId.toString())
        .withClientIp(clientIp)
        .withOldValue(Map.of(STATUS, "PENDING_REVIEW"))
        .withNewValue(Map.of(STATUS, newStatus, "customerId", customerId.toString()));
  }

  static AuditEntry profileUpdated(UUID customerId, InetAddress clientIp) {
    return byCustomer(PROFILE_UPDATE, CUSTOMER, customerId, customerId, clientIp);
  }

  /**
   * @param oldStatus null for a new application
   */
  static AuditEntry application(
      String action,
      UUID customerId,
      UUID applicationId,
      String oldStatus,
      String newStatus,
      InetAddress clientIp) {
    AuditEntry entry =
        byCustomer(action, KYC_APPLICATION, customerId, applicationId, clientIp)
            .withNewValue(Map.of(STATUS, newStatus));
    return oldStatus == null ? entry : entry.withOldValue(Map.of(STATUS, oldStatus));
  }

  private static AuditEntry byCustomer(
      String action, String resource, UUID customerId, UUID resourceId, InetAddress clientIp) {
    return AuditEntry.of(ActorType.CUSTOMER, action, resource, AuditResult.SUCCESS)
        .withActor(customerId)
        .withResourceId(resourceId.toString())
        .withClientIp(clientIp);
  }
}
