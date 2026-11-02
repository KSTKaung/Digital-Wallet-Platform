package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.EmailAddress;
import com.portfolio.wallet.auth.domain.OtpFailure;
import com.portfolio.wallet.auth.domain.OtpIssueDecision;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import com.portfolio.wallet.auth.domain.PasswordPolicy;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.AccountRow;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.LoginFailureRepository;
import com.portfolio.wallet.auth.persistence.PasswordResetTokenRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Forgotten and changed passwords (documentations/06 MVP-ADR-23). Only {@code ACTIVE} accounts get
 * a reset, and an account locked by login failures is still {@code ACTIVE}; a pending, frozen or
 * suspended account costs the same work and receives nothing, so a reset cannot give it access.
 *
 * <p>Hashing runs before the transaction, because it is slow. The transaction then replaces the
 * password only if it is still the one those checks ran against, so two concurrent changes cannot
 * both win or skip the history check.
 */
@Service
public class PasswordService {

  private static final int RESET_TOKEN_BYTES = 32;
  private static final String ACTION_RESET_REQUEST = "PASSWORD_RESET_REQUEST";
  private static final String ACTION_RESET = "PASSWORD_RESET";
  private static final String ACTION_CHANGE = "PASSWORD_CHANGE";
  private static final String REASON_INVALID_TOKEN = "INVALID_TOKEN";
  private static final String REASON_LENGTH = "POLICY_LENGTH";
  private static final String REASON_REUSED = "POLICY_REUSED";
  private static final String REASON_WRONG_CURRENT = "WRONG_CURRENT";
  private static final String REASON_CONCURRENT_CHANGE = "CONCURRENT_CHANGE";
  private static final String REASON_UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";

  /** The outcome of a code check; {@link #toString()} leaves the token out. */
  private record ResetGrant(String token, OtpFailure failure) {
    @Override
    public String toString() {
      return "ResetGrant[]";
    }
  }

  private final UserRepository users;
  private final CredentialRepository credentials;
  private final PasswordResetTokenRepository resetTokens;
  private final LoginFailureRepository unknownFailures;
  private final SessionService sessions;
  private final OtpService otps;
  private final PasswordHasher passwords;
  private final PasswordPolicy policy;
  private final KeyedHasher hasher;
  private final AuditService audit;
  private final AuthProperties properties;
  private final TransactionOperations tx;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public PasswordService(
      UserRepository users,
      CredentialRepository credentials,
      PasswordResetTokenRepository resetTokens,
      LoginFailureRepository unknownFailures,
      SessionService sessions,
      OtpService otps,
      PasswordHasher passwords,
      PasswordPolicy policy,
      KeyedHasher hasher,
      AuditService audit,
      AuthProperties properties,
      TransactionOperations tx,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.resetTokens = resetTokens;
    this.unknownFailures = unknownFailures;
    this.sessions = sessions;
    this.otps = otps;
    this.passwords = passwords;
    this.policy = policy;
    this.hasher = hasher;
    this.audit = audit;
    this.properties = properties;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Always succeeds for the caller, and {@code resend-otp} with this purpose comes here too. A
   * challenge is created whoever asks, so the work and the limits are the same; only an eligible
   * account is sent its code.
   */
  public void requestReset(String email, InetAddress clientIp) {
    String canonical = EmailAddress.canonical(email);
    Optional<AccountRow> account = users.findAccountByEmail(canonical);
    boolean eligible = account.filter(PasswordService::canReset).isPresent();
    tx.executeWithoutResult(
        status -> {
          OtpIssueDecision decision =
              otps.issue(canonical, OtpPurpose.PASSWORD_RESET, eligible, clientIp);
          boolean issued = eligible && decision == OtpIssueDecision.ALLOWED;
          String refusal =
              eligible
                  ? decision.name()
                  : account.map(row -> row.status().name()).orElse(REASON_UNKNOWN_ACCOUNT);
          audit.record(
              AuthAudit.anonymous(
                  ACTION_RESET_REQUEST,
                  AuthAudit.USER,
                  account.map(row -> row.id().toString()).orElse(null),
                  issued ? AuditResult.SUCCESS : AuditResult.DENIED,
                  issued ? null : refusal,
                  clientIp));
        });
  }

  /**
   * Exchanges a right code for a reset token. Every failure is the same {@code INVALID_OTP}, and
   * the failed attempt's count commits before it is reported.
   *
   * @return the token, which exists in plain form only in this response
   */
  public String verifyResetCode(String identifier, String code, InetAddress clientIp) {
    String canonical = EmailAddress.canonical(identifier);
    ResetGrant grant =
        tx.execute(
            status -> {
              ResetGrant result = checkCodeAndGrant(canonical, code);
              if (result.failure() == null) {
                audit.record(otpVerifyEntry(canonical, AuditResult.SUCCESS, null, clientIp));
              }
              return result;
            });
    if (grant.failure() != null) {
      audit.recordIndependently(
          otpVerifyEntry(canonical, AuditResult.FAILURE, grant.failure().name(), clientIp));
      throw new ApiException(ErrorCode.INVALID_OTP, OtpService.INVALID_OTP_MESSAGE);
    }
    return grant.token();
  }

  private AuditEntry otpVerifyEntry(
      String canonical, AuditResult result, String reason, InetAddress clientIp) {
    return AuthAudit.anonymous(
        "OTP_VERIFY",
        AuthAudit.OTP_CHALLENGE,
        hasher.identifier(canonical),
        result,
        reason,
        clientIp);
  }

  /**
   * A password the policy refuses leaves the token usable, so the token is consumed only in the
   * final transaction. It rolls back if the password changed meanwhile, which also leaves it
   * usable.
   */
  public void resetPassword(String token, String newPassword, InetAddress clientIp) {
    String tokenHash = hasher.resetToken(token);
    Optional<AccountRow> found =
        resetTokens
            .findUsableOwner(tokenHash, clock.instant())
            .flatMap(users::findAccountById)
            .filter(PasswordService::canReset);
    if (found.isEmpty()) {
      throw invalidToken(null, clientIp);
    }
    AccountRow account = found.get();
    String newHash =
        hashIfAcceptable(
            account,
            newPassword,
            reason ->
                audit.recordIndependently(
                    resetEntry(account, AuditResult.FAILURE, reason, clientIp)));
    boolean done =
        Boolean.TRUE.equals(
            tx.execute(
                status -> {
                  Instant now = clock.instant();
                  if (resetTokens.consume(tokenHash, UserStatus.ACTIVE.name(), now)
                      && replace(account, newHash, ACTION_RESET, now)) {
                    audit.record(resetEntry(account, AuditResult.SUCCESS, null, clientIp));
                    return true;
                  }
                  status.setRollbackOnly();
                  return false;
                }));
    if (!done) {
      throw invalidToken(account, clientIp);
    }
  }

  /**
   * The caller's bearer token has already passed the session check. A wrong current password is
   * audited but does not count towards the login lockout, so a stolen access token cannot lock its
   * owner out; the per-IP limit on {@code /auth/**} bounds the guessing.
   */
  public void changePassword(
      UUID userId, String currentPassword, String newPassword, InetAddress clientIp) {
    AccountRow account = users.findAccountById(userId).orElseThrow(PasswordService::unauthorized);
    String candidate = policy.isOverMaximum(currentPassword) ? "" : currentPassword;
    if (!passwords.matches(candidate, account.passwordHash())) {
      audit.recordIndependently(
          changeEntry(account, AuditResult.FAILURE, REASON_WRONG_CURRENT, clientIp));
      throw unauthorized();
    }
    String newHash =
        hashIfAcceptable(
            account,
            newPassword,
            reason ->
                audit.recordIndependently(
                    changeEntry(account, AuditResult.FAILURE, reason, clientIp)));
    boolean done =
        Boolean.TRUE.equals(
            tx.execute(
                status -> {
                  // A code or token asked for before this change must not outlive it. Lock order:
                  // identifier, challenge, reset tokens, password row, which is the order of every
                  // reset path, so this adds no deadlock.
                  Instant now = clock.instant();
                  otps.cancelLive(account.email(), OtpPurpose.PASSWORD_RESET, now);
                  resetTokens.invalidateUnused(account.id(), now);
                  if (replace(account, newHash, ACTION_CHANGE, now)) {
                    audit.record(changeEntry(account, AuditResult.SUCCESS, null, clientIp));
                    return true;
                  }
                  status.setRollbackOnly();
                  return false;
                }));
    if (!done) {
      audit.recordIndependently(
          changeEntry(account, AuditResult.FAILURE, REASON_CONCURRENT_CHANGE, clientIp));
      throw unauthorized();
    }
  }

  private static boolean canReset(AccountRow account) {
    return account.status() == UserStatus.ACTIVE;
  }

  private ResetGrant checkCodeAndGrant(String canonical, String code) {
    Optional<OtpFailure> failure = otps.check(canonical, OtpPurpose.PASSWORD_RESET, code);
    if (failure.isPresent()) {
      return new ResetGrant(null, failure.get());
    }
    return users
        .findAccountByEmail(canonical)
        .filter(PasswordService::canReset)
        .map(row -> new ResetGrant(newResetToken(row.id()), null))
        .orElseGet(() -> new ResetGrant(null, OtpFailure.ACCOUNT_NOT_ELIGIBLE));
  }

  private String newResetToken(UUID userId) {
    Instant now = clock.instant();
    byte[] bytes = new byte[RESET_TOKEN_BYTES];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    resetTokens.invalidateUnused(userId, now);
    resetTokens.insert(userId, hasher.resetToken(token), now.plus(properties.token().resetTtl()));
    return token;
  }

  private String hashIfAcceptable(
      AccountRow account, String newPassword, Consumer<String> auditRefusal) {
    if (!policy.isValidLength(newPassword)) {
      auditRefusal.accept(REASON_LENGTH);
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          "newPassword: must have "
              + policy.minLength()
              + " to "
              + policy.maxLength()
              + " characters");
    }
    List<String> recent = credentials.recentHashes(account.id(), policy.historyDepth());
    if (policy.reusesRecent(recent, hash -> passwords.matches(newPassword, hash))) {
      auditRefusal.accept(REASON_REUSED);
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR, "newPassword: must not repeat a recent password");
    }
    return passwords.hash(newPassword);
  }

  /**
   * Runs inside the caller's transaction. Sessions are revoked after the update has locked the
   * password row: a login that verified the old password either commits its session first, so this
   * revokes it, or finds the password changed and opens none.
   *
   * @return false, with nothing changed, when the password is no longer the one that was checked
   */
  private boolean replace(AccountRow account, String newHash, String reason, Instant now) {
    if (!credentials.replacePassword(account.id(), account.passwordHash(), newHash, now)) {
      return false;
    }
    credentials.addToHistory(account.id(), newHash);
    credentials.pruneHistory(account.id(), policy.historyDepth());
    unknownFailures.delete(hasher.identifier(account.email()));
    sessions.revokeAllForUser(account.id(), reason, now);
    return true;
  }

  private ApiException invalidToken(AccountRow account, InetAddress clientIp) {
    AuditEntry entry =
        account == null
            ? AuthAudit.anonymous(
                ACTION_RESET,
                AuthAudit.USER,
                null,
                AuditResult.FAILURE,
                REASON_INVALID_TOKEN,
                clientIp)
            : resetEntry(account, AuditResult.FAILURE, REASON_INVALID_TOKEN, clientIp);
    audit.recordIndependently(entry);
    return new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid or expired reset token");
  }

  private static AuditEntry resetEntry(
      AccountRow account, AuditResult result, String reason, InetAddress clientIp) {
    return AuthAudit.anonymous(
        ACTION_RESET, AuthAudit.USER, account.id().toString(), result, reason, clientIp);
  }

  private static AuditEntry changeEntry(
      AccountRow account, AuditResult result, String reason, InetAddress clientIp) {
    return AuthAudit.authenticated(
        ACTION_CHANGE, account.id(), account.role(), result, reason, clientIp);
  }

  private static ApiException unauthorized() {
    return new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required");
  }
}
