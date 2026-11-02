package com.portfolio.wallet.audit.application;

import com.portfolio.wallet.common.security.AccessDenial;
import com.portfolio.wallet.common.security.AccessDenialAuditor;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Audits refused requests in their own transaction (documentations/03 §7.1). {@code 401} refusals
 * do not come here: nothing but the per-IP limit stands in front of them, and an unauthenticated
 * flood must not be able to fill an append-only table.
 */
@Service
public class AccessDenialAuditService implements AccessDenialAuditor {

  private static final String RESOURCE = "HTTP_REQUEST";
  private static final int MAX_PATH = 200;

  private final AuditService audit;

  public AccessDenialAuditService(AuditService audit) {
    this.audit = audit;
  }

  @Override
  public void record(AccessDenial denial) {
    audit.recordIndependently(
        AuditEntry.of(actorType(denial), denial.kind().name(), RESOURCE, AuditResult.DENIED)
            .withActor(denial.actorId())
            .withClientIp(denial.clientIp())
            .withNewValue(Map.of("method", denial.method(), "path", bounded(denial.path()))));
  }

  private static ActorType actorType(AccessDenial denial) {
    if (denial.actorId() == null) {
      return ActorType.ANONYMOUS;
    }
    return denial.staff() ? ActorType.STAFF : ActorType.CUSTOMER;
  }

  private static String bounded(String path) {
    return path.length() > MAX_PATH ? path.substring(0, MAX_PATH) : path;
  }
}
