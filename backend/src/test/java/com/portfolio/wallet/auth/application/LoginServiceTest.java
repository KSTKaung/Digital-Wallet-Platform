package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.AccountRow;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.LockChange;
import com.portfolio.wallet.auth.persistence.LoginFailureRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class LoginServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
  private static final String EMAIL = "user@example.test";
  private static final String PASSWORD = "a-long-password";
  private static final String HASH = "$argon2id$stored";

  private final UserRepository users = mock(UserRepository.class);
  private final CredentialRepository credentials = mock(CredentialRepository.class);
  private final LoginFailureRepository unknown = mock(LoginFailureRepository.class);
  private final SessionService sessions = mock(SessionService.class);
  private final PasswordHasher passwords = mock(PasswordHasher.class);
  private final EmailDispatcher emails = mock(EmailDispatcher.class);
  private final AuditService audit = mock(AuditService.class);
  private final KeyedHasher hasher = new KeyedHasher(AuthTestFixtures.SECRETS);
  private final LoginService service =
      new LoginService(
          users,
          credentials,
          unknown,
          sessions,
          passwords,
          AuthTestFixtures.PASSWORDS,
          AuthTestFixtures.LOCKOUT,
          hasher,
          emails,
          audit,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private final UUID id = UUID.randomUUID();

  private void account(UserStatus status, Instant lockedUntil) {
    when(users.findAccountByEmail(EMAIL))
        .thenReturn(
            Optional.of(new AccountRow(id, EMAIL, status, Role.CUSTOMER, HASH, lockedUntil)));
  }

  private void assertLoginFails(String password) {
    assertThatThrownBy(() -> service.login(EMAIL, password, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
              assertThat(e.getMessage()).isEqualTo("Authentication required");
            });
  }

  private List<AuditEntry> audited() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit, org.mockito.Mockito.atLeastOnce()).record(entry.capture());
    return entry.getAllValues();
  }

  private void assertAuditReason(String reason) {
    assertThat(audited().getFirst().newValue()).containsEntry("reason", reason);
  }

  @Test
  void anUnknownAccountRunsADummyHashAndIsCounted() {
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
    when(unknown.findLockedUntil(hasher.identifier(EMAIL))).thenReturn(Optional.empty());
    when(unknown.recordFailure(eq(hasher.identifier(EMAIL)), eq(5), any(), eq(NOW)))
        .thenReturn(Optional.of(new LockChange(false)));

    assertLoginFails(PASSWORD);

    verify(passwords).dummyVerify(PASSWORD);
    assertAuditReason("UNKNOWN_ACCOUNT");
    assertThat(audited()).hasSize(1);
  }

  @Test
  void theFailureThatLocksAnUnknownEmailIsAuditedAndEmailsNobody() {
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
    when(unknown.findLockedUntil(any())).thenReturn(Optional.empty());
    when(unknown.recordFailure(any(), anyInt(), any(), any()))
        .thenReturn(Optional.of(new LockChange(true)));

    assertLoginFails(PASSWORD);

    assertThat(audited()).extracting(AuditEntry::action).contains("LOGIN_LOCKOUT");
    verify(emails, never()).sendAfterCommit(any());
  }

  @Test
  void aLockedUnknownEmailIsNotCountedAgain() {
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
    when(unknown.findLockedUntil(any())).thenReturn(Optional.of(NOW.plusSeconds(1)));

    assertLoginFails(PASSWORD);

    verify(passwords).dummyVerify(PASSWORD);
    verify(unknown, never()).recordFailure(any(), anyInt(), any(), any());
    assertAuditReason("LOCKED");
  }

  @Test
  void aLockedAccountRunsADummyHashEvenForTheRightPassword() {
    account(UserStatus.ACTIVE, NOW.plusSeconds(1));

    assertLoginFails(PASSWORD);

    verify(passwords).dummyVerify(PASSWORD);
    verify(passwords, never()).matches(anyString(), anyString());
    verify(credentials, never()).recordFailure(any(), anyInt(), any(), any());
    verify(sessions, never()).open(any(), any());
    assertAuditReason("LOCKED");
  }

  @Test
  void aWrongPasswordIsCountedWithTheConfiguredLock() {
    account(UserStatus.ACTIVE, null);
    when(passwords.matches(PASSWORD, HASH)).thenReturn(false);
    when(credentials.recordFailure(any(), anyInt(), any(), any()))
        .thenReturn(Optional.of(new LockChange(false)));

    assertLoginFails(PASSWORD);

    verify(credentials).recordFailure(id, 5, NOW.plus(Duration.ofMinutes(15)), NOW);
    verify(emails, never()).sendAfterCommit(any());
    assertAuditReason("WRONG_PASSWORD");
  }

  @Test
  void theFailureThatStartsALockEmailsTheOwner() {
    account(UserStatus.ACTIVE, null);
    when(credentials.recordFailure(any(), anyInt(), any(), any()))
        .thenReturn(Optional.of(new LockChange(true)));

    assertLoginFails(PASSWORD);

    ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emails).sendAfterCommit(message.capture());
    assertThat(message.getValue().kind()).isEqualTo(EmailKind.ACCOUNT_LOCKED);
    assertThat(message.getValue().to()).isEqualTo(EMAIL);
    assertThat(audited()).extracting(AuditEntry::action).contains("LOGIN_LOCKOUT");
  }

  @Test
  void aConcurrentFailureOnALockedRowSendsNoSecondEmail() {
    account(UserStatus.ACTIVE, null);
    when(credentials.recordFailure(any(), anyInt(), any(), any())).thenReturn(Optional.empty());

    assertLoginFails(PASSWORD);

    verify(emails, never()).sendAfterCommit(any());
  }

  @Test
  void aNonActiveAccountIsRefusedWithTheRightPasswordAndCounted() {
    account(UserStatus.FROZEN, null);
    when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
    when(credentials.recordFailure(any(), anyInt(), any(), any()))
        .thenReturn(Optional.of(new LockChange(false)));

    assertLoginFails(PASSWORD);

    verify(sessions, never()).open(any(), any());
    assertAuditReason("NOT_ACTIVE");
  }

  @Test
  void anOverlongPasswordIsNeverHashed() {
    account(UserStatus.ACTIVE, null);
    when(credentials.recordFailure(any(), anyInt(), any(), any()))
        .thenReturn(Optional.of(new LockChange(false)));

    assertLoginFails("x".repeat(129));

    verify(passwords).matches("", HASH);
  }

  @Test
  void aSuccessResetsOpensASessionAndIsAudited() {
    account(UserStatus.ACTIVE, NOW.minusSeconds(1));
    when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
    when(credentials.resetFailures(id, HASH, NOW)).thenReturn(true);
    SessionService.OpenedSession opened =
        new SessionService.OpenedSession(UUID.randomUUID(), "refresh");
    when(sessions.open(id, NOW)).thenReturn(opened);
    IssuedTokens tokens = new IssuedTokens("access", 900, "refresh", Duration.ofDays(7));
    when(sessions.tokens(id, Role.CUSTOMER, opened, NOW)).thenReturn(tokens);

    assertThat(service.login(EMAIL, PASSWORD, null)).isSameAs(tokens);

    assertThat(audited().getFirst().action()).isEqualTo("LOGIN");
    verify(credentials, times(1)).resetFailures(id, HASH, NOW);
  }

  @Test
  void aLockThatStartedDuringTheHashStillRefusesTheRightPassword() {
    account(UserStatus.ACTIVE, null);
    when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
    when(credentials.resetFailures(id, HASH, NOW)).thenReturn(false);
    when(users.findAccountById(id))
        .thenReturn(
            Optional.of(
                new AccountRow(
                    id, EMAIL, UserStatus.ACTIVE, Role.CUSTOMER, HASH, NOW.plusSeconds(1))));

    assertLoginFails(PASSWORD);

    verify(sessions, never()).open(any(), any());
    assertAuditReason("LOCKED");
  }

  @Test
  void aPasswordChangedDuringTheHashIsAuditedAsAConcurrentChange() {
    account(UserStatus.ACTIVE, null);
    when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
    when(credentials.resetFailures(id, HASH, NOW)).thenReturn(false);
    when(users.findAccountById(id))
        .thenReturn(
            Optional.of(
                new AccountRow(
                    id, EMAIL, UserStatus.ACTIVE, Role.CUSTOMER, "$argon2id$new", null)));

    assertLoginFails(PASSWORD);

    verify(sessions, never()).open(any(), any());
    assertAuditReason("CONCURRENT_CHANGE");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = UserStatus.class,
      names = {"PENDING_VERIFICATION", "FROZEN", "SUSPENDED"})
  void aNonActiveAccountCountsTowardsTheLockButItsAddressGetsNoEmail(UserStatus status) {
    account(status, null);
    when(credentials.recordFailure(any(), anyInt(), any(), any()))
        .thenReturn(Optional.of(new LockChange(true)));

    assertLoginFails(PASSWORD);

    verify(emails, never()).sendAfterCommit(any());
    assertThat(audited()).extracting(AuditEntry::action).contains("LOGIN_LOCKOUT");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Role.class,
      names = {"ADMIN", "COMPLIANCE_OFFICER"})
  void aStaffLoginIsAuditedAsStaff(Role role) {
    when(users.findAccountByEmail(EMAIL))
        .thenReturn(Optional.of(new AccountRow(id, EMAIL, UserStatus.ACTIVE, role, HASH, null)));
    when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
    when(credentials.resetFailures(id, HASH, NOW)).thenReturn(true);
    when(sessions.open(id, NOW))
        .thenReturn(new SessionService.OpenedSession(UUID.randomUUID(), "refresh"));

    service.login(EMAIL, PASSWORD, null);

    assertThat(audited().getFirst().actorType().name()).isEqualTo("STAFF");
    assertThat(audited().getFirst().actorId()).isEqualTo(id);
  }
}
