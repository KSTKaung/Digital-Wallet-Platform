package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.AccountRow;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(OutputCaptureExtension.class)
class StaffBootstrapRunnerTest {

  private static final String EMAIL = "staff@example.test";
  private static final String PASSWORD = "a-staff-password-long";

  private final UserRepository users = mock(UserRepository.class);
  private final CredentialRepository credentials = mock(CredentialRepository.class);
  private final PasswordHasher passwords = mock(PasswordHasher.class);
  private final AuditService audit = mock(AuditService.class);
  private final MockEnvironment environment = new MockEnvironment();
  private final StaffBootstrapRunner runner =
      new StaffBootstrapRunner(
          environment,
          users,
          credentials,
          passwords,
          AuthTestFixtures.PASSWORDS,
          audit,
          TransactionOperations.withoutTransaction(),
          Clock.systemUTC());

  private void admin(String email, String password) {
    environment.setProperty(StaffBootstrapRunner.ADMIN_EMAIL, email);
    environment.setProperty(StaffBootstrapRunner.ADMIN_PASSWORD, password);
  }

  private void existing(Role role) {
    when(users.findAccountByEmail(EMAIL))
        .thenReturn(
            Optional.of(
                new AccountRow(UUID.randomUUID(), EMAIL, UserStatus.ACTIVE, role, "h", null)));
  }

  private void assertRefusedAudit() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordIndependently(entry.capture());
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.DENIED);
    assertThat(entry.getValue().newValue()).containsEntry("reason", "EMAIL_TAKEN");
  }

  @Test
  void withoutVariablesItDoesNothing() {
    runner.run(null);
    verifyNoInteractions(users, credentials, passwords, audit);
  }

  @Test
  void blankVariablesCountAsUnset() {
    admin(" ", "");
    runner.run(null);
    verifyNoInteractions(users);
  }

  @Test
  void bothRolesAreProvisionedInACanonicalForm() {
    admin("Staff@Example.TEST", PASSWORD);
    environment.setProperty(StaffBootstrapRunner.COMPLIANCE_EMAIL, "officer@example.test");
    environment.setProperty(StaffBootstrapRunner.COMPLIANCE_PASSWORD, PASSWORD);
    when(users.findAccountByEmail(any())).thenReturn(Optional.empty());
    when(users.insertStaffIfAbsent(any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
    when(passwords.hash(PASSWORD)).thenReturn("$argon2id$x");

    runner.run(null);

    verify(users).insertStaffIfAbsent(EMAIL, Role.ADMIN);
    verify(users).insertStaffIfAbsent("officer@example.test", Role.COMPLIANCE_OFFICER);
  }

  @Test
  void aPlaceholderInAValueIsTakenLiterallyAndNotLogged(CapturedOutput output) {
    String literal = "${substituted}-staff-password";
    admin(EMAIL, literal);
    environment.setProperty("substituted", "the-other-value");
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.insertStaffIfAbsent(EMAIL, Role.ADMIN)).thenReturn(Optional.of(UUID.randomUUID()));
    when(passwords.hash(literal)).thenReturn("$argon2id$x");

    runner.run(null);

    verify(passwords).hash(literal);
    assertThat(output.getAll()).doesNotContain(literal).doesNotContain("the-other-value");
  }

  @Test
  void anIncompletePairStopsStartupNamingTheVariables() {
    environment.setProperty(StaffBootstrapRunner.COMPLIANCE_EMAIL, EMAIL);
    assertThatThrownBy(() -> runner.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(StaffBootstrapRunner.COMPLIANCE_PASSWORD)
        .hasMessageNotContaining(EMAIL);
  }

  @Test
  void aPolicyFailureStopsStartupNamingTheVariableAndNeverTheValue() {
    String tooShort = "short-pass";
    admin(EMAIL, tooShort);
    assertThatThrownBy(() -> runner.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(StaffBootstrapRunner.ADMIN_PASSWORD)
        .hasMessageNotContaining(tooShort);
    verify(passwords, never()).hash(any());
  }

  @Test
  void aMalformedEmailStopsStartupWithoutPrintingIt() {
    admin("not-an-address", PASSWORD);
    assertThatThrownBy(() -> runner.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(StaffBootstrapRunner.ADMIN_EMAIL)
        .hasMessageNotContaining("not-an-address");
  }

  @Test
  void anExistingAccountOfTheRoleIsLeftAloneWithoutHashing() {
    admin(EMAIL, PASSWORD);
    existing(Role.ADMIN);

    runner.run(null);

    verify(passwords, never()).hash(any());
    verify(users, never()).insertStaffIfAbsent(any(), any());
    verifyNoInteractions(credentials, audit);
  }

  @Test
  void anEmailHeldByAnotherRoleIsRefusedWithoutStoppingStartup(CapturedOutput output) {
    admin(EMAIL, PASSWORD);
    existing(Role.CUSTOMER);

    runner.run(null);

    verify(users, never()).insertStaffIfAbsent(any(), any());
    verifyNoInteractions(credentials);
    assertRefusedAudit();
    assertThat(output.getAll())
        .contains(StaffBootstrapRunner.ADMIN_EMAIL)
        .doesNotContain(EMAIL)
        .doesNotContain(PASSWORD);
  }

  @Test
  void losingTheInsertRaceIsAlsoARefusal() {
    admin(EMAIL, PASSWORD);
    when(users.findAccountByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.insertStaffIfAbsent(EMAIL, Role.ADMIN)).thenReturn(Optional.empty());

    runner.run(null);

    verifyNoInteractions(credentials);
    assertRefusedAudit();
  }
}
