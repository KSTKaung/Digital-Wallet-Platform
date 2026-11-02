package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.auth.domain.Role;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;

/** Builds this module's audit entries. Values are labels and ids, never secrets. */
final class AuthAudit {

  static final String USER = "USER";
  static final String SESSION = "SESSION";
  static final String OTP_CHALLENGE = "OTP_CHALLENGE";

  private AuthAudit() {}

  /** An action by someone not yet authenticated; the resource says which account or identifier. */
  static AuditEntry anonymous(
      String action,
      String resource,
      String resourceId,
      AuditResult result,
      String reason,
      InetAddress clientIp) {
    return entry(ActorType.ANONYMOUS, null, action, resource, resourceId, result, reason, clientIp);
  }

  /** An action by a known user; the role says whether that is a customer or staff. */
  static AuditEntry forRole(
      String action,
      UUID actorId,
      Role role,
      String resource,
      String resourceId,
      AuditResult result,
      String reason,
      InetAddress clientIp) {
    return entry(
        role == Role.CUSTOMER ? ActorType.CUSTOMER : ActorType.STAFF,
        actorId,
        action,
        resource,
        resourceId,
        result,
        reason,
        clientIp);
  }

  /** An authenticated user acting on their own account. */
  static AuditEntry authenticated(
      String action,
      UUID actorId,
      Role role,
      AuditResult result,
      String reason,
      InetAddress clientIp) {
    return forRole(action, actorId, role, USER, actorId.toString(), result, reason, clientIp);
  }

  /** An action by the application itself; the caller adds any values. */
  static AuditEntry system(String action, String resource, String resourceId, AuditResult result) {
    return entry(ActorType.SYSTEM, null, action, resource, resourceId, result, null, null);
  }

  /** For a row whose actor is not the account concerned, so that it still says which one was. */
  static Map<String, Object> reasonAndOwner(String reason, UUID ownerId) {
    return Map.of("reason", reason, "ownerId", ownerId.toString());
  }

  private static AuditEntry entry(
      ActorType actorType,
      UUID actorId,
      String action,
      String resource,
      String resourceId,
      AuditResult result,
      String reason,
      InetAddress clientIp) {
    AuditEntry entry =
        AuditEntry.of(actorType, action, resource, result)
            .withActor(actorId)
            .withResourceId(resourceId)
            .withClientIp(clientIp);
    return reason == null ? entry : entry.withNewValue(Map.of("reason", reason));
  }
}
