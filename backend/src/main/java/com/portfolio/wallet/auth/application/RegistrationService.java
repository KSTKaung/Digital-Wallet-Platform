package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.EmailAddress;
import com.portfolio.wallet.auth.domain.OtpFailure;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import com.portfolio.wallet.auth.domain.PasswordPolicy;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.AccountRow;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.LockedUser;
import com.portfolio.wallet.auth.persistence.OwnerNoticeRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.notification.application.EmailDispatcher;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Registration and its OTP (documentations/06 MVP-ADR-23). Responses never reveal whether an
 * account exists. Password hashing runs before the transaction opens, because it is slow and must
 * not hold a connection; refusals are reported after commit, because their counts must persist.
 */
@Service
public class RegistrationService {

  /** The size of {@code auth.users.email}. */
  private static final int MAX_EMAIL_LENGTH = 254;

  private static final String REASON_PENDING_REPLACED = "PENDING_REPLACED";

  private final UserRepository users;
  private final CredentialRepository credentials;
  private final OwnerNoticeRepository notices;
  private final OtpService otps;
  private final PasswordHasher passwords;
  private final PasswordPolicy passwordPolicy;
  private final KeyedHasher hasher;
  private final EmailDispatcher emails;
  private final AuditService audit;
  private final AuthProperties properties;
  private final TransactionOperations tx;
  private final Clock clock;

  public RegistrationService(
      UserRepository users,
      CredentialRepository credentials,
      OwnerNoticeRepository notices,
      OtpService otps,
      PasswordHasher passwords,
      PasswordPolicy passwordPolicy,
      KeyedHasher hasher,
      EmailDispatcher emails,
      AuditService audit,
      AuthProperties properties,
      TransactionOperations tx,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.notices = notices;
    this.properties = properties;
    this.otps = otps;
    this.passwords = passwords;
    this.passwordPolicy = passwordPolicy;
    this.hasher = hasher;
    this.emails = emails;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * @return the canonical email, which is all the response may say about the account
   */
  public String register(String email, String phoneNumber, String password, InetAddress clientIp) {
    String canonical = EmailAddress.canonical(email);
    // Lower-casing can lengthen a string past what the request's size check saw.
    if (canonical.length() > MAX_EMAIL_LENGTH) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, "email: size is invalid");
    }
    if (!passwordPolicy.isValidLength(password)) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          "password: must have "
              + passwordPolicy.minLength()
              + " to "
              + passwordPolicy.maxLength()
              + " characters");
    }
    String hash = passwords.hash(password);
    tx.executeWithoutResult(status -> registerLocked(canonical, phoneNumber, hash, clientIp));
    return canonical;
  }

  /**
   * Lock order: the phone, then the identifier, then the user row, then the challenge. The
   * identifier comes before the row because {@code verify} holds it while it activates, and before
   * the insert because a second registration would otherwise wait on the unique index while holding
   * what the first one needs. The phone's owner is locked last, against the purge only, which never
   * waits on a row.
   */
  private void registerLocked(
      String canonical, String phoneNumber, String hash, InetAddress clientIp) {
    users.lockPhone(phoneNumber);
    otps.lockIdentifier(canonical);
    if (insertNew(canonical, phoneNumber, hash, clientIp)) {
      return;
    }
    Optional<LockedUser> emailOwner = users.lockByEmail(canonical);
    Optional<AccountRow> phoneOwner = users.lockOwnerOfPhone(phoneNumber);
    if (emailOwner.isEmpty()) {
      // Only the phone conflicted; or the purge removed the account after the insert saw it.
      if (phoneOwner.isPresent()) {
        notifyOwner(phoneOwner.get().id(), phoneOwner.get().email());
        auditAlreadyRegistered(clientIp);
      } else if (!insertNew(canonical, phoneNumber, hash, clientIp)) {
        auditAlreadyRegistered(clientIp);
      }
      return;
    }
    LockedUser account = emailOwner.get();
    if (account.status() != UserStatus.PENDING_VERIFICATION) {
      notifyOwner(account.id(), canonical);
      auditAlreadyRegistered(clientIp);
    } else if (phoneOwner.filter(owner -> !owner.id().equals(account.id())).isPresent()) {
      notifyOwner(phoneOwner.get().id(), phoneOwner.get().email());
      auditAlreadyRegistered(clientIp);
    } else {
      replacePending(account.id(), canonical, phoneNumber, phoneOwner.isEmpty(), hash, clientIp);
    }
  }

  private boolean insertNew(
      String canonical, String phoneNumber, String hash, InetAddress clientIp) {
    Optional<UUID> created = users.insertCustomerIfAbsent(canonical, phoneNumber);
    if (created.isEmpty()) {
      return false;
    }
    credentials.insert(created.get(), hash, clock.instant());
    otps.issue(canonical, OtpPurpose.REGISTRATION, true, clientIp);
    auditRegistered(created.get(), null, clientIp);
    return true;
  }

  /**
   * Whoever controls the mailbox wins an account nobody has verified: this takes over the password
   * and phone, and ends any code issued for the password it replaces, even when the limits then
   * refuse a new code.
   */
  private void replacePending(
      UUID id,
      String canonical,
      String phoneNumber,
      boolean phoneChanges,
      String hash,
      InetAddress clientIp) {
    Instant now = clock.instant();
    if (phoneChanges) {
      users.changePhone(id, phoneNumber);
    }
    credentials.overwritePending(id, hash, now);
    otps.cancelLive(canonical, OtpPurpose.REGISTRATION, now);
    otps.issue(canonical, OtpPurpose.REGISTRATION, true, clientIp);
    auditRegistered(id, REASON_PENDING_REPLACED, clientIp);
  }

  /**
   * Every failure is the same {@code INVALID_OTP}; the audit row keeps the real reason. For a
   * registration the code proves the mailbox and the password proves who registered, so a stranger
   * who registered first cannot have the mailbox owner activate the stranger's password. The
   * password is checked on every path, before any transaction, so timing says nothing either.
   *
   * @param password may be null, which simply is a wrong password
   */
  public void verify(
      String identifier, String purposeName, String code, String password, InetAddress clientIp) {
    String canonical = EmailAddress.canonical(identifier);
    String candidate = password == null || passwordPolicy.isOverMaximum(password) ? "" : password;
    Optional<AccountRow> account = users.findAccountByEmail(canonical);
    boolean passwordAccepted = false;
    if (account.isPresent()) {
      passwordAccepted = passwords.matches(candidate, account.get().passwordHash());
    } else {
      passwords.dummyVerify(candidate);
    }
    boolean accepted = passwordAccepted;
    // LOGIN is refused by decision; PASSWORD_RESET goes to PasswordService.
    Optional<OtpFailure> failure =
        OtpPurpose.REGISTRATION.name().equals(purposeName)
            ? tx.execute(status -> activate(canonical, code, account, accepted, clientIp))
            : Optional.of(OtpFailure.UNSUPPORTED_PURPOSE);
    if (failure.isPresent()) {
      audit.recordIndependently(
          otpVerifyEntry(canonical, AuditResult.FAILURE, failure.get().name(), clientIp));
      throw new ApiException(ErrorCode.INVALID_OTP, OtpService.INVALID_OTP_MESSAGE);
    }
  }

  private Optional<OtpFailure> activate(
      String canonical,
      String code,
      Optional<AccountRow> account,
      boolean passwordAccepted,
      InetAddress clientIp) {
    Optional<OtpFailure> refused =
        otps.check(canonical, OtpPurpose.REGISTRATION, code, passwordAccepted);
    if (refused.isPresent()) {
      return refused;
    }
    // A right code implies the password was accepted, and so that the account was found.
    if (!users.activatePending(canonical, account.orElseThrow().passwordHash())) {
      return Optional.of(OtpFailure.ACCOUNT_NOT_PENDING);
    }
    audit.record(otpVerifyEntry(canonical, AuditResult.SUCCESS, null, clientIp));
    return Optional.empty();
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

  /** Always succeeds for the caller; limits and unknown identifiers silently send nothing. */
  public void resend(String identifier, String purposeName, InetAddress clientIp) {
    if (!OtpPurpose.REGISTRATION.name().equals(purposeName)) {
      return;
    }
    String canonical = EmailAddress.canonical(identifier);
    boolean pending =
        users
            .findAccountByEmail(canonical)
            .filter(account -> account.status() == UserStatus.PENDING_VERIFICATION)
            .isPresent();
    tx.executeWithoutResult(
        status -> otps.issue(canonical, OtpPurpose.REGISTRATION, pending, clientIp));
  }

  /**
   * At most one notice per owner per interval, so registering cannot be used to flood a mailbox.
   */
  private void notifyOwner(UUID ownerId, String address) {
    Instant now = clock.instant();
    if (notices.claim(ownerId, now, now.minus(properties.ownerNoticeInterval()))) {
      emails.sendAfterCommit(AuthEmails.existingAccount(address));
    }
  }

  private void auditRegistered(UUID id, String reason, InetAddress clientIp) {
    audit.record(
        AuthAudit.anonymous(
            "REGISTER", AuthAudit.USER, id.toString(), AuditResult.SUCCESS, reason, clientIp));
  }

  private void auditAlreadyRegistered(InetAddress clientIp) {
    audit.record(
        AuthAudit.anonymous(
            "REGISTER", AuthAudit.USER, null, AuditResult.DENIED, "ALREADY_REGISTERED", clientIp));
  }
}
