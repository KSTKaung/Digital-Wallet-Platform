package com.portfolio.wallet.audit.application;

import com.portfolio.wallet.common.security.AccessDenial;
import com.portfolio.wallet.common.security.AccessDenialAuditor;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Audits refused requests in their own transaction (documentations/03 §7.1). {@code 401} refusals
 * do not come here: nothing but the per-IP limit stands in front of them, and an unauthenticated
 * flood must not be able to fill an append-only table.
 *
 * <p>{@code ACCESS_DENIED} for a known user is capped at one row per user per {@link
 * AuditProperties#accessDeniedWindow()} (owner, 2026-11-03; documentations/08 §4B.2 Q12); further
 * refusals in the window are logged only. The cap is in memory: per instance, and reset on restart
 * ({@link AccessDenialThrottle}). Refusals without a known user, and {@code CSRF_REFUSED}, which
 * never has one, are not capped: there is no user to count them against.
 */
@Service
public class AccessDenialAuditService implements AccessDenialAuditor {

  private static final Logger log = LoggerFactory.getLogger(AccessDenialAuditService.class);
  private static final String RESOURCE = "HTTP_REQUEST";
  private static final int MAX_PATH = 200;

  private final AuditService audit;
  private final AccessDenialThrottle throttle;

  public AccessDenialAuditService(AuditService audit, AuditProperties properties, Clock clock) {
    this.audit = audit;
    this.throttle =
        new AccessDenialThrottle(
            properties.accessDeniedWindow(), properties.accessDeniedMaxTrackedUsers(), clock);
  }

  /**
   * A refusal the cap leaves unaudited is logged at INFO with the user id, the method and the path
   * (J1, J8), so it can still be traced: the path is the request URI, without query string, body or
   * headers, cut to {@value #MAX_PATH} characters. The two reasons have separate messages.
   */
  @Override
  public void record(AccessDenial denial) {
    AccessDenialThrottle.Decision decision = decide(denial);
    if (decision == AccessDenialThrottle.Decision.IN_WINDOW) {
      log.info(
          "Access denied; not audited, the user's audit row for this window exists:"
              + " userId={}, method={}, path={}",
          denial.actorId(),
          denial.method(),
          bounded(denial.path()));
      return;
    }
    if (decision == AccessDenialThrottle.Decision.FULL) {
      log.info(
          "Access denied; not audited, the audit cap already tracks its maximum number of users:"
              + " userId={}, method={}, path={}",
          denial.actorId(),
          denial.method(),
          bounded(denial.path()));
      return;
    }
    try {
      audit.recordIndependently(
          AuditEntry.of(actorType(denial), denial.kind().name(), RESOURCE, AuditResult.DENIED)
              .withActor(denial.actorId())
              .withClientIp(denial.clientIp())
              .withNewValue(Map.of("method", denial.method(), "path", bounded(denial.path()))));
    } catch (RuntimeException e) {
      // No row was written, so the window must not hide the user's next refusal.
      if (denial.actorId() != null) {
        throttle.release(denial.actorId());
      }
      throw e;
    }
  }

  // Only ACCESS_DENIED with a known user is capped; everything else is audited.
  private AccessDenialThrottle.Decision decide(AccessDenial denial) {
    if (denial.kind() != AccessDenial.Kind.ACCESS_DENIED || denial.actorId() == null) {
      return AccessDenialThrottle.Decision.AUDIT;
    }
    return throttle.tryAcquire(denial.actorId());
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
