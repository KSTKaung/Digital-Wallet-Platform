package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.OtpFailure;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.AccountRow;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.LockedUser;
import com.portfolio.wallet.auth.persistence.OwnerNoticeRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.notification.application.EmailDispatcher;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

class RegistrationServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
  private static final String EMAIL = "user@example.test";
  private static final String PHONE = "+66812345678";
  private static final String PASSWORD = "a-long-password";
  private static final String HASH = "$argon2id$hash";
  private static final String STORED = "$argon2id$stored";

  private final UserRepository users = mock(UserRepository.class);
  private final CredentialRepository credentials = mock(CredentialRepository.class);
  private final OwnerNoticeRepository notices = mock(OwnerNoticeRepository.class);
  private final OtpService otps = mock(OtpService.class);
  private final PasswordHasher passwords = mock(PasswordHasher.class);
  private final EmailDispatcher emails = mock(EmailDispatcher.class);
  private final AuditService audit = mock(AuditService.class);
  private final RegistrationService service =
      new RegistrationService(
          users,
          credentials,
          notices,
          otps,
          passwords,
          AuthTestFixtures.PASSWORDS,
          new KeyedHasher(AuthTestFixtures.SECRETS),
          emails,
          audit,
          AuthTestFixtures.PROPERTIES,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private static AccountRow account(String email, UserStatus status) {
    return new AccountRow(UUID.randomUUID(), email, status, Role.CUSTOMER, STORED, null);
  }

  private EmailMessage sent() {
    ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emails).sendAfterCommit(message.capture());
    return message.getValue();
  }

  private AuditEntry auditedIndependently() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordIndependently(entry.capture());
    return entry.getValue();
  }

  private AuditEntry audited() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    return entry.getValue();
  }

  private void conflict() {
    when(users.insertCustomerIfAbsent(EMAIL, PHONE)).thenReturn(Optional.empty());
    when(passwords.hash(PASSWORD)).thenReturn(HASH);
    when(notices.claim(any(), any(), any())).thenReturn(true);
  }

  private void assertUntouched(UUID id) {
    verify(credentials, never()).insert(any(), any(), any());
    verify(credentials, never()).overwritePending(any(), any(), any());
    verify(users, never()).changePhone(any(), any());
    verify(otps, never()).cancelLive(any(), any(), any());
    verify(otps, never()).issue(any(), any(), anyBoolean(), any());
    assertThat(id).isNotNull();
  }

  @Test
  void aNewAccountIsHashedFirstThenInsertedWithAChallenge() {
    UUID id = UUID.randomUUID();
    when(passwords.hash(PASSWORD)).thenReturn(HASH);
    when(users.insertCustomerIfAbsent(EMAIL, PHONE)).thenReturn(Optional.of(id));

    assertThat(service.register("User@Example.TEST", PHONE, PASSWORD, null)).isEqualTo(EMAIL);

    InOrder order = inOrder(passwords, users, otps, credentials);
    order.verify(passwords).hash(PASSWORD);
    order.verify(users).lockPhone(PHONE);
    order.verify(otps).lockIdentifier(EMAIL);
    order.verify(users).insertCustomerIfAbsent(EMAIL, PHONE);
    order.verify(credentials).insert(id, HASH, NOW);
    order.verify(otps).issue(EMAIL, OtpPurpose.REGISTRATION, true, null);
    verify(emails, never()).sendAfterCommit(any());
  }

  @Test
  void aPendingAccountIsReplacedUnderItsLocksAndItsLiveCodeEnds() {
    UUID id = UUID.randomUUID();
    conflict();
    when(users.lockByEmail(EMAIL))
        .thenReturn(Optional.of(new LockedUser(id, UserStatus.PENDING_VERIFICATION)));
    when(users.lockOwnerOfPhone(PHONE)).thenReturn(Optional.empty());

    assertThat(service.register(EMAIL, PHONE, PASSWORD, null)).isEqualTo(EMAIL);

    InOrder order = inOrder(users, credentials, otps);
    order.verify(users).lockPhone(PHONE);
    order.verify(otps).lockIdentifier(EMAIL);
    order.verify(users).lockByEmail(EMAIL);
    order.verify(users).lockOwnerOfPhone(PHONE);
    order.verify(users).changePhone(id, PHONE);
    order.verify(credentials).overwritePending(id, HASH, NOW);
    order.verify(otps).cancelLive(EMAIL, OtpPurpose.REGISTRATION, NOW);
    order.verify(otps).issue(EMAIL, OtpPurpose.REGISTRATION, true, null);
    assertThat(audited().newValue()).containsEntry("reason", "PENDING_REPLACED");
    verify(emails, never()).sendAfterCommit(any());
  }

  @Test
  void reRegisteringThePendingAccountsOwnPhoneDoesNotRewriteIt() {
    AccountRow self = account(EMAIL, UserStatus.PENDING_VERIFICATION);
    conflict();
    when(users.lockByEmail(EMAIL))
        .thenReturn(Optional.of(new LockedUser(self.id(), UserStatus.PENDING_VERIFICATION)));
    when(users.lockOwnerOfPhone(PHONE)).thenReturn(Optional.of(self));

    service.register(EMAIL, PHONE, PASSWORD, null);

    verify(users, never()).changePhone(any(), any());
    verify(credentials).overwritePending(self.id(), HASH, NOW);
  }

  @Test
  void aPendingAccountAskingForAPhoneTakenElsewhereChangesNothingAndNotifiesThatOwner() {
    UUID id = UUID.randomUUID();
    AccountRow owner = account("owner@example.test", UserStatus.ACTIVE);
    conflict();
    when(users.lockByEmail(EMAIL))
        .thenReturn(Optional.of(new LockedUser(id, UserStatus.PENDING_VERIFICATION)));
    when(users.lockOwnerOfPhone(PHONE)).thenReturn(Optional.of(owner));

    assertThat(service.register(EMAIL, PHONE, PASSWORD, null)).isEqualTo(EMAIL);

    assertUntouched(id);
    assertThat(sent().to()).isEqualTo("owner@example.test");
    assertThat(audited().newValue()).containsEntry("reason", "ALREADY_REGISTERED");
  }

  @ParameterizedTest
  @EnumSource(
      value = UserStatus.class,
      names = {"ACTIVE", "FROZEN", "SUSPENDED"})
  void anAccountPastVerificationStillHashesChangesNothingAndNotifiesItsOwner(UserStatus status) {
    UUID id = UUID.randomUUID();
    conflict();
    when(users.lockByEmail(EMAIL)).thenReturn(Optional.of(new LockedUser(id, status)));
    when(users.lockOwnerOfPhone(PHONE)).thenReturn(Optional.empty());

    assertThat(service.register(EMAIL, PHONE, PASSWORD, null)).isEqualTo(EMAIL);

    verify(passwords).hash(PASSWORD);
    assertUntouched(id);
    assertThat(sent().kind()).isEqualTo(EmailKind.EXISTING_ACCOUNT_NOTICE);
    assertThat(sent().to()).isEqualTo(EMAIL);
    verify(notices).claim(id, NOW, NOW.minus(Duration.ofHours(1)));
  }

  @Test
  void aNoticeClaimedRecentlySendsNothingAndAnswersTheSame() {
    UUID id = UUID.randomUUID();
    conflict();
    when(users.lockByEmail(EMAIL)).thenReturn(Optional.of(new LockedUser(id, UserStatus.ACTIVE)));
    when(users.lockOwnerOfPhone(PHONE)).thenReturn(Optional.empty());
    when(notices.claim(any(), any(), any())).thenReturn(false);

    assertThat(service.register(EMAIL, PHONE, PASSWORD, null)).isEqualTo(EMAIL);

    verify(emails, never()).sendAfterCommit(any());
    assertThat(audited().newValue()).containsEntry("reason", "ALREADY_REGISTERED");
  }

  @Test
  void aTakenPhoneSendsTheNoticeToThePhonesOwner() {
    conflict();
    when(users.lockByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.lockOwnerOfPhone(PHONE))
        .thenReturn(Optional.of(account("owner@example.test", UserStatus.ACTIVE)));

    service.register(EMAIL, PHONE, PASSWORD, null);

    assertThat(sent().to()).isEqualTo("owner@example.test");
  }

  @Test
  void anAccountPurgedAfterTheConflictIsInsertedAfterAll() {
    UUID id = UUID.randomUUID();
    when(passwords.hash(PASSWORD)).thenReturn(HASH);
    when(users.insertCustomerIfAbsent(EMAIL, PHONE))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(id));
    when(users.lockByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.lockOwnerOfPhone(PHONE)).thenReturn(Optional.empty());

    assertThat(service.register(EMAIL, PHONE, PASSWORD, null)).isEqualTo(EMAIL);

    verify(credentials).insert(id, HASH, NOW);
    verify(otps).issue(EMAIL, OtpPurpose.REGISTRATION, true, null);
  }

  @Test
  void aConflictWhoseOwnerCannotBeFoundStillAnswersTheSame() {
    conflict();
    when(users.lockByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.lockOwnerOfPhone(PHONE)).thenReturn(Optional.empty());

    assertThat(service.register(EMAIL, PHONE, PASSWORD, null)).isEqualTo(EMAIL);

    verify(emails, never()).sendAfterCommit(any());
    assertThat(audited().result()).isEqualTo(AuditResult.DENIED);
  }

  @ParameterizedTest
  @ValueSource(ints = {11, 129})
  void aBadLengthIsAValidationErrorBeforeAnyHashing(int length) {
    assertThatThrownBy(() -> service.register(EMAIL, PHONE, "x".repeat(length), null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.getMessage()).contains("password").doesNotContain("xxx");
            });
    verify(passwords, never()).hash(anyString());
  }

  @Test
  void anEmailLengthenedByCanonicalisationIsRefused() {
    String tooLong = "İ".repeat(200) + "@example.test";
    assertThatThrownBy(() -> service.register(tooLong, PHONE, PASSWORD, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  // Verify --------------------------------------------------------------------------------------

  private void pendingAccount(boolean rightPassword) {
    when(users.findAccountByEmail(EMAIL))
        .thenReturn(Optional.of(account(EMAIL, UserStatus.PENDING_VERIFICATION)));
    when(passwords.matches(PASSWORD, STORED)).thenReturn(rightPassword);
  }

  @Test
  void aRightCodeAndPasswordActivateWithTheSuccessAuditInsideTheTransaction() {
    pendingAccount(true);
    when(otps.check(EMAIL, OtpPurpose.REGISTRATION, "123456", true)).thenReturn(Optional.empty());
    when(users.activatePending(EMAIL, STORED)).thenReturn(true);

    service.verify(EMAIL, "REGISTRATION", "123456", PASSWORD, null);

    InOrder order = inOrder(passwords, otps, users, audit);
    order.verify(passwords).matches(PASSWORD, STORED);
    order.verify(otps).check(EMAIL, OtpPurpose.REGISTRATION, "123456", true);
    order.verify(users).activatePending(EMAIL, STORED);
    order.verify(audit).record(any());
    assertThat(audited().result()).isEqualTo(AuditResult.SUCCESS);
    verify(audit, never()).recordIndependently(any());
  }

  private void assertInvalidOtp(String purpose, String password, String reason) {
    assertThatThrownBy(() -> service.verify(EMAIL, purpose, "123456", password, null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_OTP));
    AuditEntry entry = auditedIndependently();
    assertThat(entry.newValue()).containsEntry("reason", reason);
    assertThat(entry.resourceId())
        .isEqualTo(new KeyedHasher(AuthTestFixtures.SECRETS).identifier(EMAIL));
    verify(audit, never()).record(any());
  }

  @Test
  void aFailedCheckIsInvalidOtpWithTheRealReasonAudited() {
    pendingAccount(true);
    when(otps.check(EMAIL, OtpPurpose.REGISTRATION, "123456", true))
        .thenReturn(Optional.of(OtpFailure.EXPIRED));
    assertInvalidOtp("REGISTRATION", PASSWORD, "EXPIRED");
    verify(users, never()).activatePending(any(), any());
  }

  @Test
  void aWrongPasswordTellsTheCheckWhichCountsItAsAnAttempt() {
    pendingAccount(false);
    when(otps.check(EMAIL, OtpPurpose.REGISTRATION, "123456", false))
        .thenReturn(Optional.of(OtpFailure.WRONG_PASSWORD));
    assertInvalidOtp("REGISTRATION", PASSWORD, "WRONG_PASSWORD");
  }

  @Test
  void aMissingPasswordCostsTheSameHashAndIsAWrongPassword() {
    when(users.findAccountByEmail(EMAIL))
        .thenReturn(Optional.of(account(EMAIL, UserStatus.PENDING_VERIFICATION)));
    when(otps.check(EMAIL, OtpPurpose.REGISTRATION, "123456", false))
        .thenReturn(Optional.of(OtpFailure.WRONG_PASSWORD));
    assertInvalidOtp("REGISTRATION", null, "WRONG_PASSWORD");
    verify(passwords).matches("", STORED);
  }

  @Test
  void anOverlongPasswordCostsOneHashOfNothing() {
    when(users.findAccountByEmail(EMAIL))
        .thenReturn(Optional.of(account(EMAIL, UserStatus.PENDING_VERIFICATION)));
    when(otps.check(EMAIL, OtpPurpose.REGISTRATION, "123456", false))
        .thenReturn(Optional.of(OtpFailure.WRONG_PASSWORD));
    assertInvalidOtp("REGISTRATION", "x".repeat(129), "WRONG_PASSWORD");
    verify(passwords).matches("", STORED);
  }

  @Test
  void anUnknownAccountRunsADummyHash() {
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
    when(otps.check(EMAIL, OtpPurpose.REGISTRATION, "123456", false))
        .thenReturn(Optional.of(OtpFailure.NO_CHALLENGE));
    assertInvalidOtp("REGISTRATION", PASSWORD, "NO_CHALLENGE");
    verify(passwords).dummyVerify(PASSWORD);
  }

  @Test
  void aRightCodeForAnAccountNotPendingIsInvalidOtp() {
    pendingAccount(true);
    when(otps.check(EMAIL, OtpPurpose.REGISTRATION, "123456", true)).thenReturn(Optional.empty());
    when(users.activatePending(EMAIL, STORED)).thenReturn(false);
    assertInvalidOtp("REGISTRATION", PASSWORD, "ACCOUNT_NOT_PENDING");
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOGIN", "PASSWORD_RESET"})
  void otherPurposesAreInvalidOtpWithoutTouchingAChallenge(String purpose) {
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
    assertInvalidOtp(purpose, PASSWORD, "UNSUPPORTED_PURPOSE");
    verify(passwords).dummyVerify(PASSWORD);
    verify(otps, never()).check(any(), any(), any(), anyBoolean());
  }

  // Resend --------------------------------------------------------------------------------------

  @Test
  void resendDeliversOnlyToAPendingAccount() {
    when(users.findAccountByEmail(EMAIL))
        .thenReturn(Optional.of(account(EMAIL, UserStatus.PENDING_VERIFICATION)));
    service.resend(EMAIL, "REGISTRATION", null);
    verify(otps).issue(EMAIL, OtpPurpose.REGISTRATION, true, null);
  }

  @Test
  void resendForAnActiveOrUnknownAccountIssuesWithoutDelivery() {
    when(users.findAccountByEmail(EMAIL))
        .thenReturn(Optional.of(account(EMAIL, UserStatus.ACTIVE)))
        .thenReturn(Optional.empty());
    service.resend(EMAIL, "REGISTRATION", null);
    service.resend(EMAIL, "REGISTRATION", null);
    verify(otps, times(2)).issue(eq(EMAIL), eq(OtpPurpose.REGISTRATION), eq(false), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOGIN", "PASSWORD_RESET"})
  void resendForOtherPurposesDoesNothing(String purpose) {
    service.resend(EMAIL, purpose, null);
    verify(otps, never()).issue(any(), any(), anyBoolean(), any());
  }
}
