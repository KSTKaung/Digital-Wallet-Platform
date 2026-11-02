package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.OtpChallengeState;
import com.portfolio.wallet.auth.domain.OtpFailure;
import com.portfolio.wallet.auth.domain.OtpIssueHistory;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import com.portfolio.wallet.auth.persistence.OtpChallengeRepository;
import com.portfolio.wallet.auth.persistence.OtpChallengeRow;
import com.portfolio.wallet.notification.application.EmailDispatcher;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OtpServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
  private static final String EMAIL = "user@example.test";
  private static final OtpIssueHistory NO_HISTORY = new OtpIssueHistory(null, null, 0, 0, 0);

  private final OtpChallengeRepository repository = mock(OtpChallengeRepository.class);
  private final EmailDispatcher emails = mock(EmailDispatcher.class);
  private final AuditService audit = mock(AuditService.class);
  private final KeyedHasher hasher = new KeyedHasher(AuthTestFixtures.SECRETS);
  private final String identifierHash = hasher.identifier(EMAIL);
  private final OtpService service =
      new OtpService(
          repository,
          AuthTestFixtures.OTP,
          hasher,
          emails,
          audit,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private void history(OtpIssueHistory history) {
    when(repository.history(eq(identifierHash), eq(OtpPurpose.REGISTRATION), any(), any()))
        .thenReturn(history);
  }

  private AuditEntry audited() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    return entry.getValue();
  }

  @Test
  void issuingLocksCancelsStoresADigestAndEmailsTheCode() {
    history(NO_HISTORY);

    service.issue(EMAIL, OtpPurpose.REGISTRATION, true, null);

    verify(repository).lockIdentifier(identifierHash);
    verify(repository).cancelLive(identifierHash, OtpPurpose.REGISTRATION, NOW);
    ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emails).sendAfterCommit(message.capture());
    assertThat(message.getValue().kind()).isEqualTo(EmailKind.OTP);
    assertThat(message.getValue().to()).isEqualTo(EMAIL);
    Matcher code = Pattern.compile("code is (\\d{6})\\.").matcher(message.getValue().body());
    assertThat(code.find()).isTrue();
    verify(repository)
        .insert(
            identifierHash,
            OtpPurpose.REGISTRATION,
            hasher.otp(identifierHash, "REGISTRATION", code.group(1)),
            NOW,
            NOW.plus(AuthTestFixtures.OTP.ttl()));
    assertThat(audited().result()).isEqualTo(AuditResult.SUCCESS);
  }

  @Test
  void withoutARecipientTheSameWorkIsDoneAndNothingIsSent() {
    history(NO_HISTORY);

    service.issue(EMAIL, OtpPurpose.REGISTRATION, false, null);

    verify(repository)
        .insert(eq(identifierHash), eq(OtpPurpose.REGISTRATION), any(), eq(NOW), any());
    verify(emails, never()).sendAfterCommit(any());
  }

  @Test
  void aLimitSilentlyDoesNothingAndIsAudited() {
    history(new OtpIssueHistory(NOW, NOW, 1, 0, 0));

    service.issue(EMAIL, OtpPurpose.REGISTRATION, true, null);

    verify(repository, never()).cancelLive(any(), any(), any());
    verify(repository, never()).insert(any(), any(), any(), any(), any());
    verify(emails, never()).sendAfterCommit(any());
    AuditEntry entry = audited();
    assertThat(entry.result()).isEqualTo(AuditResult.DENIED);
    assertThat(entry.newValue()).containsEntry("reason", "COOLDOWN");
  }

  private OtpChallengeRow challenge(String code, int attempts, Instant consumedAt) {
    return new OtpChallengeRow(
        UUID.randomUUID(),
        hasher.otp(identifierHash, "REGISTRATION", code),
        new OtpChallengeState(attempts, NOW.plusSeconds(60), consumedAt, null, null));
  }

  private void latest(Optional<OtpChallengeRow> row) {
    when(repository.latestForUpdate(identifierHash, OtpPurpose.REGISTRATION)).thenReturn(row);
  }

  @Test
  void noChallenge() {
    latest(Optional.empty());
    assertThat(service.check(EMAIL, OtpPurpose.REGISTRATION, "123456"))
        .contains(OtpFailure.NO_CHALLENGE);
  }

  @Test
  void aRefusedChallengeCountsNoAttempt() {
    latest(Optional.of(challenge("123456", 0, NOW)));
    assertThat(service.check(EMAIL, OtpPurpose.REGISTRATION, "123456"))
        .contains(OtpFailure.ALREADY_CONSUMED);
    verify(repository, never())
        .recordWrongAttempt(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    verify(repository, never()).consume(any(), any());
  }

  @Test
  void theRightCodeConsumes() {
    OtpChallengeRow row = challenge("123456", 4, null);
    latest(Optional.of(row));
    assertThat(service.check(EMAIL, OtpPurpose.REGISTRATION, "123456")).isEmpty();
    verify(repository).consume(row.id(), NOW);
  }

  @Test
  void aWrongCodeCounts() {
    OtpChallengeRow row = challenge("123456", 1, null);
    latest(Optional.of(row));
    assertThat(service.check(EMAIL, OtpPurpose.REGISTRATION, "000000"))
        .contains(OtpFailure.WRONG_CODE);
    verify(repository).recordWrongAttempt(row.id(), 2, null);
  }

  @Test
  void aWrongPasswordCostsAnAttemptEvenWithTheRightCodeAndConsumesNothing() {
    OtpChallengeRow row = challenge("123456", 1, null);
    latest(Optional.of(row));
    assertThat(service.check(EMAIL, OtpPurpose.REGISTRATION, "123456", false))
        .contains(OtpFailure.WRONG_PASSWORD);
    verify(repository).recordWrongAttempt(row.id(), 2, null);
    verify(repository, never()).consume(any(), any());
  }

  @Test
  void aWrongCodeWithAWrongPasswordIsAuditedAsTheCode() {
    latest(Optional.of(challenge("123456", 0, null)));
    assertThat(service.check(EMAIL, OtpPurpose.REGISTRATION, "000000", false))
        .contains(OtpFailure.WRONG_CODE);
  }

  @Test
  void aCheckHoldsTheIdentifierLockFirst() {
    latest(Optional.of(challenge("123456", 0, null)));
    service.check(EMAIL, OtpPurpose.REGISTRATION, "123456");
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
    order.verify(repository).lockIdentifier(identifierHash);
    order.verify(repository).latestForUpdate(identifierHash, OtpPurpose.REGISTRATION);
  }

  @Test
  void cancellingLocksTheIdentifierAndEndsTheLiveCode() {
    service.cancelLive(EMAIL, OtpPurpose.PASSWORD_RESET, NOW);
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
    order.verify(repository).lockIdentifier(identifierHash);
    order.verify(repository).cancelLive(identifierHash, OtpPurpose.PASSWORD_RESET, NOW);
  }

  @Test
  void issuingReturnsItsDecision() {
    history(NO_HISTORY);
    assertThat(service.issue(EMAIL, OtpPurpose.REGISTRATION, true, null))
        .isEqualTo(com.portfolio.wallet.auth.domain.OtpIssueDecision.ALLOWED);
    history(new OtpIssueHistory(NOW, NOW, 1, 0, 0));
    assertThat(service.issue(EMAIL, OtpPurpose.REGISTRATION, true, null))
        .isEqualTo(com.portfolio.wallet.auth.domain.OtpIssueDecision.COOLDOWN);
  }

  @Test
  void theLastWrongCodeSpendsTheChallenge() {
    OtpChallengeRow row = challenge("123456", 4, null);
    latest(Optional.of(row));
    service.check(EMAIL, OtpPurpose.REGISTRATION, "000000");
    verify(repository).recordWrongAttempt(row.id(), 5, NOW);
  }
}
