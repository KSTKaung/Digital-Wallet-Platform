package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.EmailAddress;
import com.portfolio.wallet.auth.domain.LockoutPolicy;
import com.portfolio.wallet.auth.domain.PasswordPolicy;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.AccountRow;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.LockChange;
import com.portfolio.wallet.auth.persistence.LoginFailureRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.notification.application.EmailDispatcher;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Login (documentations/06 MVP-ADR-23). Every failure is the same 401 after one Argon2id
 * computation. The failure's transaction commits before the exception is thrown, so the counters
 * and the audit rows persist; hashing runs outside any transaction.
 */
@Service
public class LoginService {

  private enum Failure {
    UNKNOWN_ACCOUNT,
    LOCKED,
    WRONG_PASSWORD,
    NOT_ACTIVE,
    CONCURRENT_CHANGE
  }

  private final UserRepository users;
  private final CredentialRepository credentials;
  private final LoginFailureRepository unknownFailures;
  private final SessionService sessions;
  private final PasswordHasher passwords;
  private final PasswordPolicy passwordPolicy;
  private final LockoutPolicy lockout;
  private final KeyedHasher hasher;
  private final EmailDispatcher emails;
  private final AuditService audit;
  private final TransactionOperations tx;
  private final Clock clock;

  public LoginService(
      UserRepository users,
      CredentialRepository credentials,
      LoginFailureRepository unknownFailures,
      SessionService sessions,
      PasswordHasher passwords,
      PasswordPolicy passwordPolicy,
      LockoutPolicy lockout,
      KeyedHasher hasher,
      EmailDispatcher emails,
      AuditService audit,
      TransactionOperations tx,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.unknownFailures = unknownFailures;
    this.sessions = sessions;
    this.passwords = passwords;
    this.passwordPolicy = passwordPolicy;
    this.lockout = lockout;
    this.hasher = hasher;
    this.emails = emails;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  public IssuedTokens login(String email, String password, InetAddress clientIp) {
    String canonical = EmailAddress.canonical(email);
    String identifierHash = hasher.identifier(canonical);
    // A password over the maximum can never be right; this also bounds the work it can cause.
    String candidate = passwordPolicy.isOverMaximum(password) ? "" : password;
    Optional<AccountRow> found = users.findAccountByEmail(canonical);

    if (found.isEmpty()) {
      passwords.dummyVerify(candidate);
      boolean locked =
          lockout.isLocked(
              unknownFailures.findLockedUntil(identifierHash).orElse(null), clock.instant());
      throw locked ? failLocked(identifierHash, clientIp) : failUnknown(identifierHash, clientIp);
    }

    AccountRow account = found.get();
    if (lockout.isLocked(account.lockedUntil(), clock.instant())) {
      passwords.dummyVerify(candidate);
      throw failLocked(account.id().toString(), clientIp);
    }
    if (!passwords.matches(candidate, account.passwordHash())) {
      throw failKnown(account, Failure.WRONG_PASSWORD, clientIp);
    }
    if (account.status() != UserStatus.ACTIVE) {
      // Counted like a wrong password, so the two cannot be told apart by the lock either.
      throw failKnown(account, Failure.NOT_ACTIVE, clientIp);
    }
    return succeed(account, clientIp);
  }

  private IssuedTokens succeed(AccountRow account, InetAddress clientIp) {
    Instant now = clock.instant();
    SessionService.OpenedSession opened =
        tx.execute(
            status -> {
              // Refused if a concurrent failure just started a lock, or if the password was changed
              // after this login checked it: a reset must not be outlived by a new session.
              if (!credentials.resetFailures(account.id(), account.passwordHash(), now)) {
                return null;
              }
              SessionService.OpenedSession session = sessions.open(account.id(), now);
              audit.record(
                  AuthAudit.forRole(
                      "LOGIN",
                      account.id(),
                      account.role(),
                      AuthAudit.SESSION,
                      session.sessionId().toString(),
                      AuditResult.SUCCESS,
                      null,
                      clientIp));
              return session;
            });
    if (opened == null) {
      throw failUnchanged(account.id().toString(), lostRaceReason(account), clientIp);
    }
    return sessions.tokens(account.id(), account.role(), opened, now);
  }

  /** The refused update does not say why; only the password having changed tells them apart. */
  private Failure lostRaceReason(AccountRow account) {
    return users
            .findAccountById(account.id())
            .filter(current -> current.passwordHash().equals(account.passwordHash()))
            .isPresent()
        ? Failure.LOCKED
        : Failure.CONCURRENT_CHANGE;
  }

  private ApiException failKnown(AccountRow account, Failure reason, InetAddress clientIp) {
    Instant now = clock.instant();
    tx.executeWithoutResult(
        status -> {
          Optional<LockChange> change =
              credentials.recordFailure(
                  account.id(), lockout.maxFailedAttempts(), lockout.lockedUntil(now), now);
          auditFailure(account.id().toString(), reason, clientIp);
          if (change.filter(LockChange::started).isPresent()) {
            auditLockStart(account.id().toString(), clientIp);
            // An unverified address may not belong to whoever registered it.
            if (account.status() == UserStatus.ACTIVE) {
              emails.sendAfterCommit(AuthEmails.accountLocked(account.email(), lockout.duration()));
            }
          }
        });
    return unauthorized();
  }

  private ApiException failUnknown(String identifierHash, InetAddress clientIp) {
    Instant now = clock.instant();
    tx.executeWithoutResult(
        status -> {
          Optional<LockChange> change =
              unknownFailures.recordFailure(
                  identifierHash, lockout.maxFailedAttempts(), lockout.lockedUntil(now), now);
          auditFailure(identifierHash, Failure.UNKNOWN_ACCOUNT, clientIp);
          if (change.filter(LockChange::started).isPresent()) {
            auditLockStart(identifierHash, clientIp);
          }
        });
    return unauthorized();
  }

  private ApiException failLocked(String resourceId, InetAddress clientIp) {
    return failUnchanged(resourceId, Failure.LOCKED, clientIp);
  }

  /** A failure that counts nothing and is only audited. */
  private ApiException failUnchanged(String resourceId, Failure reason, InetAddress clientIp) {
    tx.executeWithoutResult(status -> auditFailure(resourceId, reason, clientIp));
    return unauthorized();
  }

  private void auditFailure(String resourceId, Failure reason, InetAddress clientIp) {
    audit.record(
        AuthAudit.anonymous(
            "LOGIN", AuthAudit.USER, resourceId, AuditResult.FAILURE, reason.name(), clientIp));
  }

  private void auditLockStart(String resourceId, InetAddress clientIp) {
    audit.record(
        AuthAudit.anonymous(
            "LOGIN_LOCKOUT", AuthAudit.USER, resourceId, AuditResult.DENIED, null, clientIp));
  }

  private static ApiException unauthorized() {
    return new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required");
  }
}
