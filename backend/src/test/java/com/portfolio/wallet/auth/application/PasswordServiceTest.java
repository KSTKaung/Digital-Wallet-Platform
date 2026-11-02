package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.OtpFailure;
import com.portfolio.wallet.auth.domain.OtpIssueDecision;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.AccountRow;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.LoginFailureRepository;
import com.portfolio.wallet.auth.persistence.PasswordResetTokenRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class PasswordServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
  private static final String EMAIL = "user@example.test";
  private static final String HASH = "$argon2id$current";
  private static final String NEW_HASH = "$argon2id$new";
  private static final String CURRENT = "the-current-password";
  private static final String NEXT = "the-next-password";
  private static final String TOKEN = "presented-token";

  private final UserRepository users = mock(UserRepository.class);
  private final CredentialRepository credentials = mock(CredentialRepository.class);
  private final PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);
  private final LoginFailureRepository unknownFailures = mock(LoginFailureRepository.class);
  private final SessionService sessions = mock(SessionService.class);
  private final OtpService otps = mock(OtpService.class);
  private final PasswordHasher passwords = mock(PasswordHasher.class);
  private final AuditService audit = mock(AuditService.class);
  private final KeyedHasher hasher = new KeyedHasher(AuthTestFixtures.SECRETS);
  private final PasswordService service =
      new PasswordService(
          users,
          credentials,
          resetTokens,
          unknownFailures,
          sessions,
          otps,
          passwords,
          AuthTestFixtures.PASSWORDS,
          hasher,
          audit,
          AuthTestFixtures.PROPERTIES,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private final UUID id = UUID.randomUUID();
  private final String tokenHash = hasher.resetToken(TOKEN);

  private AccountRow account(UserStatus status, Role role) {
    return new AccountRow(id, EMAIL, status, role, HASH, null);
  }

  private void storedAccount(UserStatus status) {
    AccountRow row = account(status, Role.CUSTOMER);
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.of(row));
    when(users.findAccountById(id)).thenReturn(Optional.of(row));
  }

  private void usableToken() {
    storedAccount(UserStatus.ACTIVE);
    when(resetTokens.findUsableOwner(tokenHash, NOW)).thenReturn(Optional.of(id));
    when(credentials.recentHashes(id, 5)).thenReturn(List.of(HASH));
    when(passwords.hash(NEXT)).thenReturn(NEW_HASH);
  }

  private void assertFails(Runnable call, ErrorCode code, String messagePart) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(code);
              assertThat(e.getMessage()).contains(messagePart);
            });
  }

  private AuditEntry independentAudit() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordIndependently(entry.capture());
    return entry.getValue();
  }

  // Request --------------------------------------------------------------------------------------

  @Test
  void anActiveAccountIsSentACodeAndOnlyAnIssuedCodeIsASuccess() {
    storedAccount(UserStatus.ACTIVE);
    when(otps.issue(EMAIL, OtpPurpose.PASSWORD_RESET, true, null))
        .thenReturn(OtpIssueDecision.ALLOWED);
    service.requestReset("User@Example.TEST", null);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.SUCCESS);
  }

  @Test
  void aRefusedIssueOnAnEligibleAccountIsNoSuccess() {
    storedAccount(UserStatus.ACTIVE);
    when(otps.issue(EMAIL, OtpPurpose.PASSWORD_RESET, true, null))
        .thenReturn(OtpIssueDecision.COOLDOWN);
    service.requestReset(EMAIL, null);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.DENIED);
    assertThat(entry.getValue().newValue()).containsEntry("reason", "COOLDOWN");
  }

  @ParameterizedTest
  @EnumSource(
      value = UserStatus.class,
      names = {"PENDING_VERIFICATION", "FROZEN", "SUSPENDED"})
  void anAccountNotActiveCostsTheSameWorkAndIsSentNothing(UserStatus status) {
    storedAccount(status);
    service.requestReset(EMAIL, null);
    verify(otps).issue(EMAIL, OtpPurpose.PASSWORD_RESET, false, null);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().newValue()).containsEntry("reason", status.name());
  }

  @Test
  void anUnknownEmailCostsTheSameWorkAndIsSentNothing() {
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
    service.requestReset(EMAIL, null);
    verify(otps).issue(EMAIL, OtpPurpose.PASSWORD_RESET, false, null);
  }

  // Verify ---------------------------------------------------------------------------------------

  @Test
  void aRightCodeInvalidatesEarlierTokensAndStoresOnlyAHash() {
    storedAccount(UserStatus.ACTIVE);
    when(otps.check(EMAIL, OtpPurpose.PASSWORD_RESET, "123456")).thenReturn(Optional.empty());

    String token = service.verifyResetCode(EMAIL, "123456", null);

    assertThat(token).hasSizeGreaterThanOrEqualTo(43);
    verify(resetTokens).invalidateUnused(id, NOW);
    verify(resetTokens)
        .insert(
            id, hasher.resetToken(token), NOW.plus(AuthTestFixtures.PROPERTIES.token().resetTtl()));
    ArgumentCaptor<AuditEntry> success = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(success.capture());
    assertThat(success.getValue().action()).isEqualTo("OTP_VERIFY");
    assertThat(success.getValue().resourceId()).isEqualTo(hasher.identifier(EMAIL));
    verify(audit, never()).recordIndependently(any());
  }

  @Test
  void aWrongCodeIsInvalidOtpWithTheKeyedIdentifierAudited() {
    when(otps.check(EMAIL, OtpPurpose.PASSWORD_RESET, "123456"))
        .thenReturn(Optional.of(OtpFailure.WRONG_CODE));

    assertFails(
        () -> service.verifyResetCode(EMAIL, "123456", null), ErrorCode.INVALID_OTP, "Invalid");

    AuditEntry entry = independentAudit();
    assertThat(entry.resourceId()).isEqualTo(hasher.identifier(EMAIL));
    assertThat(entry.newValue()).containsEntry("reason", "WRONG_CODE");
  }

  @Test
  void aRightCodeForAFrozenAccountIsInvalidOtpAndGrantsNothing() {
    storedAccount(UserStatus.FROZEN);
    when(otps.check(EMAIL, OtpPurpose.PASSWORD_RESET, "123456")).thenReturn(Optional.empty());

    assertFails(
        () -> service.verifyResetCode(EMAIL, "123456", null), ErrorCode.INVALID_OTP, "Invalid");

    verify(resetTokens, never()).insert(any(), any(), any());
    assertThat(independentAudit().newValue()).containsEntry("reason", "ACCOUNT_NOT_ELIGIBLE");
  }

  // Reset ----------------------------------------------------------------------------------------

  @Test
  void aSuccessfulResetAppliesEveryConsequence() {
    usableToken();
    when(resetTokens.consume(tokenHash, "ACTIVE", NOW)).thenReturn(true);
    when(credentials.replacePassword(id, HASH, NEW_HASH, NOW)).thenReturn(true);

    service.resetPassword(TOKEN, NEXT, null);

    verify(credentials).addToHistory(id, NEW_HASH);
    verify(credentials).pruneHistory(id, 5);
    verify(unknownFailures).delete(hasher.identifier(EMAIL));
    verify(sessions).revokeAllForUser(id, "PASSWORD_RESET", NOW);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.SUCCESS);
  }

  @Test
  void anUnusableTokenIsRefusedBeforeAnyHashing() {
    when(resetTokens.findUsableOwner(tokenHash, NOW)).thenReturn(Optional.empty());

    assertFails(
        () -> service.resetPassword(TOKEN, NEXT, null),
        ErrorCode.VALIDATION_ERROR,
        "Invalid or expired reset token");

    verify(passwords, never()).hash(any());
  }

  @Test
  void aTokenOfAFrozenAccountIsRefused() {
    storedAccount(UserStatus.FROZEN);
    when(resetTokens.findUsableOwner(tokenHash, NOW)).thenReturn(Optional.of(id));

    assertFails(
        () -> service.resetPassword(TOKEN, NEXT, null),
        ErrorCode.VALIDATION_ERROR,
        "Invalid or expired reset token");

    verify(resetTokens, never()).consume(any(), any(), any());
  }

  @Test
  void aPolicyFailureDoesNotConsumeTheToken() {
    usableToken();
    when(passwords.matches(NEXT, HASH)).thenReturn(true);

    assertFails(
        () -> service.resetPassword(TOKEN, NEXT, null), ErrorCode.VALIDATION_ERROR, "newPassword");
    assertFails(
        () -> service.resetPassword(TOKEN, "short", null),
        ErrorCode.VALIDATION_ERROR,
        "newPassword");

    verify(resetTokens, never()).consume(any(), any(), any());
  }

  @Test
  void losingTheTokenChangesNothing() {
    usableToken();
    when(resetTokens.consume(tokenHash, "ACTIVE", NOW)).thenReturn(false);

    assertFails(
        () -> service.resetPassword(TOKEN, NEXT, null),
        ErrorCode.VALIDATION_ERROR,
        "Invalid or expired reset token");

    verify(credentials, never()).replacePassword(any(), any(), any(), any());
  }

  @Test
  void aPasswordChangedMeanwhileRefusesTheResetAndRevokesNothing() {
    usableToken();
    when(resetTokens.consume(tokenHash, "ACTIVE", NOW)).thenReturn(true);
    when(credentials.replacePassword(id, HASH, NEW_HASH, NOW)).thenReturn(false);

    assertFails(
        () -> service.resetPassword(TOKEN, NEXT, null),
        ErrorCode.VALIDATION_ERROR,
        "Invalid or expired reset token");

    verify(sessions, never()).revokeAllForUser(any(), any(), any());
  }

  // Change ---------------------------------------------------------------------------------------

  private void changeable() {
    storedAccount(UserStatus.ACTIVE);
    when(passwords.matches(CURRENT, HASH)).thenReturn(true);
    when(credentials.recentHashes(id, 5)).thenReturn(List.of(HASH));
    when(passwords.hash(NEXT)).thenReturn(NEW_HASH);
  }

  @Test
  void aSuccessfulChangeRevokesAllSessionsAndAuditsTheActor() {
    changeable();
    when(credentials.replacePassword(id, HASH, NEW_HASH, NOW)).thenReturn(true);

    service.changePassword(id, CURRENT, NEXT, null);

    verify(sessions).revokeAllForUser(id, "PASSWORD_CHANGE", NOW);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().actorId()).isEqualTo(id);
    assertThat(entry.getValue().actorType().name()).isEqualTo("CUSTOMER");
  }

  @Test
  void aChangeEndsLiveCodesAndTokensBeforeItTakesThePasswordRow() {
    changeable();
    when(credentials.replacePassword(id, HASH, NEW_HASH, NOW)).thenReturn(true);

    service.changePassword(id, CURRENT, NEXT, null);

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(otps, resetTokens, credentials);
    order.verify(otps).cancelLive(EMAIL, OtpPurpose.PASSWORD_RESET, NOW);
    order.verify(resetTokens).invalidateUnused(id, NOW);
    order.verify(credentials).replacePassword(id, HASH, NEW_HASH, NOW);
  }

  @Test
  void aStaffChangeIsAuditedAsStaff() {
    changeable();
    when(users.findAccountById(id)).thenReturn(Optional.of(account(UserStatus.ACTIVE, Role.ADMIN)));
    when(credentials.replacePassword(id, HASH, NEW_HASH, NOW)).thenReturn(true);

    service.changePassword(id, CURRENT, NEXT, null);

    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().actorType().name()).isEqualTo("STAFF");
  }

  @Test
  void aWrongCurrentIsTheGeneric401AndNeverTouchesTheLockout() {
    storedAccount(UserStatus.ACTIVE);

    assertFails(
        () -> service.changePassword(id, "wrong-password", NEXT, null),
        ErrorCode.UNAUTHORIZED,
        "Authentication required");

    verify(credentials, never()).recordFailure(any(), anyInt(), any(), any());
    assertThat(independentAudit().newValue()).containsEntry("reason", "WRONG_CURRENT");
  }

  @Test
  void anOversizedCurrentPasswordIsNotHashed() {
    storedAccount(UserStatus.ACTIVE);

    assertFails(
        () -> service.changePassword(id, "x".repeat(129), NEXT, null),
        ErrorCode.UNAUTHORIZED,
        "Authentication required");

    verify(passwords).matches("", HASH);
  }

  @Test
  void aMissingAccountIsTheGeneric401() {
    when(users.findAccountById(id)).thenReturn(Optional.empty());
    assertFails(
        () -> service.changePassword(id, CURRENT, NEXT, null),
        ErrorCode.UNAUTHORIZED,
        "Authentication required");
  }

  @Test
  void aReusedPasswordIsRefusedNamingTheField() {
    changeable();
    when(passwords.matches(NEXT, HASH)).thenReturn(true);

    assertFails(
        () -> service.changePassword(id, CURRENT, NEXT, null),
        ErrorCode.VALIDATION_ERROR,
        "newPassword");

    assertThat(independentAudit().newValue()).containsEntry("reason", "POLICY_REUSED");
  }

  @Test
  void losingAConcurrentChangeIsTheGeneric401() {
    changeable();
    when(credentials.replacePassword(id, HASH, NEW_HASH, NOW)).thenReturn(false);

    assertFails(
        () -> service.changePassword(id, CURRENT, NEXT, null),
        ErrorCode.UNAUTHORIZED,
        "Authentication required");

    verify(sessions, never()).revokeAllForUser(any(), any(), any());
    assertThat(independentAudit().newValue()).containsEntry("reason", "CONCURRENT_CHANGE");
  }

  @Test
  void aFailureInsideTheTransactionPropagates() {
    usableToken();
    when(resetTokens.consume(tokenHash, "ACTIVE", NOW)).thenReturn(true);
    when(credentials.replacePassword(eq(id), any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("boom")).when(credentials).addToHistory(any(), any());

    assertThatThrownBy(() -> service.resetPassword(TOKEN, NEXT, null))
        .isInstanceOf(IllegalStateException.class);
  }
}
