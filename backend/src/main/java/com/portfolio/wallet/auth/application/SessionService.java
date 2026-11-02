package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.SessionPolicy;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.RefreshTokenRepository;
import com.portfolio.wallet.auth.persistence.RefreshTokenRow;
import com.portfolio.wallet.auth.persistence.SessionRepository;
import com.portfolio.wallet.auth.persistence.SessionRow;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.security.SessionValidator;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Sessions and refresh tokens (documentations/03 §3.4; doc 06 MVP-ADR-14, -23). A session is the
 * token family. Refusals are reported after commit, so a revocation survives the failed request.
 */
@Service
public class SessionService implements SessionValidator {

  private static final int REFRESH_TOKEN_BYTES = 32;
  private static final String REASON_LOGOUT = "LOGOUT";
  private static final String REASON_TOKEN_REUSE = "TOKEN_REUSE";
  private static final String REASON_IDLE = "IDLE_TIMEOUT";

  /** A new session and its first refresh token; {@link #toString()} leaves the token out. */
  record OpenedSession(UUID sessionId, String refreshToken) {
    @Override
    public String toString() {
      return "OpenedSession[" + sessionId + "]";
    }
  }

  /** The result of rotating; {@link #toString()} leaves the token out. */
  private record Rotation(
      UUID userId, Role role, UUID sessionId, String refreshToken, Instant expiresAt) {
    @Override
    public String toString() {
      return "Rotation[" + sessionId + "]";
    }
  }

  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;
  private final SessionPolicy policy;
  private final KeyedHasher hasher;
  private final AccessTokenIssuer accessTokens;
  private final AuditService audit;
  private final AuthProperties properties;
  private final TransactionOperations tx;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public SessionService(
      SessionRepository sessions,
      RefreshTokenRepository refreshTokens,
      SessionPolicy policy,
      KeyedHasher hasher,
      AccessTokenIssuer accessTokens,
      AuditService audit,
      AuthProperties properties,
      TransactionOperations tx,
      Clock clock) {
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.policy = policy;
    this.hasher = hasher;
    this.accessTokens = accessTokens;
    this.audit = audit;
    this.properties = properties;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Runs on each authenticated request. No transaction: the read and the throttled write each
   * auto-commit, so a request with no write due only reads. An idle session is revoked with {@code
   * IDLE_TIMEOUT}, not merely refused, so the record shows why it ended.
   */
  @Override
  public boolean isActive(UUID sessionId, UUID subject) {
    Optional<SessionRow> found = sessions.find(sessionId);
    if (found.isEmpty()
        || !found.get().userId().equals(subject)
        || found.get().revokedAt() != null
        || found.get().userStatus() != UserStatus.ACTIVE) {
      return false;
    }
    SessionRow session = found.get();
    Instant now = clock.instant();
    if (policy.isIdle(session.lastSeenAt(), now)) {
      tx.executeWithoutResult(status -> revokeIdle(session, now));
      return false;
    }
    if (policy.isLastSeenWriteDue(session.lastSeenAt(), now)) {
      sessions.touch(sessionId, now, policy.lastSeenWriteCutoff(now));
    }
    return true;
  }

  /** Runs inside the login's transaction. */
  OpenedSession open(UUID userId, Instant now) {
    UUID sessionId = sessions.insert(userId, now);
    return new OpenedSession(
        sessionId, newRefreshToken(sessionId, now.plus(properties.token().refreshTtl())));
  }

  /** Signs the access token; called after commit, so nothing is issued for a rolled-back login. */
  IssuedTokens tokens(UUID userId, Role role, OpenedSession opened, Instant now) {
    return issued(
        userId,
        role,
        opened.sessionId(),
        opened.refreshToken(),
        properties.token().refreshTtl(),
        now);
  }

  /** Runs inside the caller's transaction: sessions first, because that is what requests check. */
  void revokeAllForUser(UUID userId, String reason, Instant now) {
    sessions.revokeAllForUser(userId, reason, now);
    refreshTokens.revokeAllForUser(userId, now);
  }

  /**
   * Rotates under the session's and then the token's row lock. No grace period: of two concurrent
   * uses of one token, the second counts as reuse and revokes the family (documentations/08 §4A.6).
   */
  public IssuedTokens refresh(String presentedToken, InetAddress clientIp) {
    if (presentedToken == null || presentedToken.isBlank()) {
      throw unauthorized();
    }
    String tokenHash = hasher.refreshToken(presentedToken);
    Instant now = clock.instant();
    Rotation rotation = tx.execute(status -> rotate(tokenHash, now, clientIp));
    if (rotation == null) {
      throw unauthorized();
    }
    return issued(
        rotation.userId(),
        rotation.role(),
        rotation.sessionId(),
        rotation.refreshToken(),
        Duration.between(now, rotation.expiresAt()),
        now);
  }

  public void logout(UUID sessionId, UUID userId, InetAddress clientIp) {
    Instant now = clock.instant();
    tx.executeWithoutResult(
        status -> {
          sessions.revoke(sessionId, REASON_LOGOUT, now);
          refreshTokens.revokeAll(sessionId, now);
          // The session was checked moments ago, on this very request.
          Role role = sessions.find(sessionId).map(SessionRow::userRole).orElse(Role.CUSTOMER);
          audit.record(
              AuthAudit.forRole(
                  "LOGOUT",
                  userId,
                  role,
                  AuthAudit.SESSION,
                  sessionId.toString(),
                  AuditResult.SUCCESS,
                  null,
                  clientIp));
        });
  }

  /**
   * The lock order of every path here is session, then tokens. Which session to lock comes from an
   * unlocked read, and the token's state is read again once its lock is held. A purge may delete
   * either in between; that token is then unknown like any other.
   */
  private Rotation rotate(String tokenHash, Instant now, InetAddress clientIp) {
    Optional<SessionRow> locked =
        refreshTokens.findSessionId(tokenHash).flatMap(sessions::findForUpdate);
    Optional<RefreshTokenRow> found =
        locked.isPresent() ? refreshTokens.findForUpdate(tokenHash) : Optional.empty();
    if (found.isEmpty()) {
      auditRefreshRefused(null, null, "UNKNOWN_TOKEN", clientIp);
      return null;
    }
    RefreshTokenRow token = found.get();
    SessionRow session = locked.get();
    Optional<String> refusal = refusal(token, session, now, clientIp);
    if (refusal.isPresent()) {
      auditRefreshRefused(session.userId(), session.id(), refusal.get(), clientIp);
      return null;
    }
    refreshTokens.markUsed(token.id(), now);
    sessions.touch(session.id(), now, now);
    // The absolute cap: the login's token expired at login + the refresh lifetime and each rotation
    // inherits the earlier expiry, so activity never extends a session past it. This reads the
    // application's clock, unlike sessions.created_at, which the database sets.
    Instant expiresAt = earlier(now.plus(properties.token().refreshTtl()), token.expiresAt());
    String newToken = newRefreshToken(session.id(), expiresAt);
    audit.record(
        AuthAudit.forRole(
            "TOKEN_REFRESH",
            session.userId(),
            session.userRole(),
            AuthAudit.SESSION,
            session.id().toString(),
            AuditResult.SUCCESS,
            null,
            clientIp));
    return new Rotation(session.userId(), session.userRole(), session.id(), newToken, expiresAt);
  }

  private static Instant earlier(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private Optional<String> refusal(
      RefreshTokenRow token, SessionRow session, Instant now, InetAddress clientIp) {
    if (token.revokedAt() != null) {
      return Optional.of("REVOKED_TOKEN");
    }
    if (token.usedAt() != null) {
      if (sessions.revoke(session.id(), REASON_TOKEN_REUSE, now)) {
        auditRevokedOnRefusal(session, REASON_TOKEN_REUSE, clientIp);
      }
      refreshTokens.revokeAll(session.id(), now);
      return Optional.of(REASON_TOKEN_REUSE);
    }
    if (!token.expiresAt().isAfter(now)) {
      return Optional.of("EXPIRED_TOKEN");
    }
    if (session.revokedAt() != null) {
      return Optional.of("REVOKED_SESSION");
    }
    if (policy.isIdle(session.lastSeenAt(), now)) {
      if (sessions.revoke(session.id(), REASON_IDLE, now)) {
        refreshTokens.revokeAll(session.id(), now);
        auditRevokedOnRefusal(session, REASON_IDLE, clientIp);
      }
      return Optional.of(REASON_IDLE);
    }
    return session.userStatus() == UserStatus.ACTIVE
        ? Optional.empty()
        : Optional.of("USER_NOT_ACTIVE");
  }

  /**
   * A refused refresh proves nothing about who presented the token, so the system is the actor and
   * the owner is only named.
   */
  private void auditRevokedOnRefusal(SessionRow session, String reason, InetAddress clientIp) {
    audit.record(
        AuthAudit.system(
                "SESSION_REVOKED", AuthAudit.SESSION, session.id().toString(), AuditResult.DENIED)
            .withClientIp(clientIp)
            .withNewValue(AuthAudit.reasonAndOwner(reason, session.userId())));
  }

  private void revokeIdle(SessionRow session, Instant now) {
    if (sessions.revoke(session.id(), REASON_IDLE, now)) {
      refreshTokens.revokeAll(session.id(), now);
      audit.record(
          AuthAudit.forRole(
              "SESSION_REVOKED",
              session.userId(),
              session.userRole(),
              AuthAudit.SESSION,
              session.id().toString(),
              AuditResult.SUCCESS,
              REASON_IDLE,
              null));
    }
  }

  /**
   * Whoever presented a refused token has proven nothing, and may have stolen it, so the owner is
   * named but not recorded as the actor.
   *
   * @param ownerId the account the token belongs to; null for an unknown token
   */
  private void auditRefreshRefused(
      UUID ownerId, UUID sessionId, String reason, InetAddress clientIp) {
    AuditEntry entry =
        AuthAudit.anonymous(
            "TOKEN_REFRESH",
            AuthAudit.SESSION,
            sessionId == null ? null : sessionId.toString(),
            AuditResult.DENIED,
            reason,
            clientIp);
    audit.record(
        ownerId == null ? entry : entry.withNewValue(AuthAudit.reasonAndOwner(reason, ownerId)));
  }

  private String newRefreshToken(UUID sessionId, Instant expiresAt) {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    refreshTokens.insert(sessionId, hasher.refreshToken(token), expiresAt);
    return token;
  }

  private IssuedTokens issued(
      UUID userId,
      Role role,
      UUID sessionId,
      String refreshToken,
      Duration refreshMaxAge,
      Instant now) {
    // The absolute cap applies here too: refreshMaxAge is the time left until the session ends.
    Duration accessTtl = properties.token().accessTtl();
    Duration accessLifetime = accessTtl.compareTo(refreshMaxAge) < 0 ? accessTtl : refreshMaxAge;
    return new IssuedTokens(
        accessTokens.issue(userId, role, sessionId, now, now.plus(accessLifetime)),
        accessLifetime.toSeconds(),
        refreshToken,
        refreshMaxAge);
  }

  private static ApiException unauthorized() {
    return new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required");
  }
}
