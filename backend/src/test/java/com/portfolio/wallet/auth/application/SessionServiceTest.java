package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.RefreshTokenRepository;
import com.portfolio.wallet.auth.persistence.RefreshTokenRow;
import com.portfolio.wallet.auth.persistence.SessionRepository;
import com.portfolio.wallet.auth.persistence.SessionRow;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class SessionServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
  private static final Duration IDLE = Duration.ofMinutes(30);
  private static final String TOKEN = "presented-token";

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final KeyedHasher hasher = new KeyedHasher(AuthTestFixtures.SECRETS);
  private final AccessTokenIssuer issuer =
      new AccessTokenIssuer(
          AuthTestFixtures.CONFIG.jwtEncoder(AuthTestFixtures.SECRETS),
          new JwtProperties("test-issuer"),
          AuthTestFixtures.SECRETS);
  private final TransactionOperations tx = mock(TransactionOperations.class);
  private final SessionService service =
      new SessionService(
          sessions,
          tokens,
          AuthTestFixtures.SESSION,
          hasher,
          issuer,
          audit,
          AuthTestFixtures.PROPERTIES,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private final UUID sessionId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  private SessionRow session(Instant lastSeen, Instant revoked, UserStatus status) {
    return new SessionRow(sessionId, userId, lastSeen, revoked, status, Role.CUSTOMER);
  }

  private void stored(SessionRow row) {
    when(sessions.find(sessionId)).thenReturn(Optional.of(row));
    when(sessions.findForUpdate(sessionId)).thenReturn(Optional.of(row));
  }

  private void storedToken(Instant expires, Instant used, Instant revoked) {
    when(tokens.findSessionId(hasher.refreshToken(TOKEN))).thenReturn(Optional.of(sessionId));
    when(tokens.findForUpdate(hasher.refreshToken(TOKEN)))
        .thenReturn(
            Optional.of(new RefreshTokenRow(UUID.randomUUID(), sessionId, expires, used, revoked)));
  }

  @Test
  void aRotatedTokenInheritsTheExpirySoActivityNeverExtendsTheSession() {
    Instant cap = NOW.plus(Duration.ofDays(2));
    storedToken(cap, null, null);
    stored(session(NOW, null, UserStatus.ACTIVE));

    IssuedTokens issued = service.refresh(TOKEN, null);

    verify(tokens).insert(eq(sessionId), eq(hasher.refreshToken(issued.refreshToken())), eq(cap));
    assertThat(issued.refreshMaxAge()).isEqualTo(Duration.ofDays(2));
  }

  private List<AuditEntry> audited() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit, org.mockito.Mockito.atLeastOnce()).record(entry.capture());
    return entry.getAllValues();
  }

  private void assertRefreshRefused(String reason) {
    assertThatThrownBy(() -> service.refresh(TOKEN, null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    assertThat(audited().getLast().newValue()).containsEntry("reason", reason);
  }

  // Per-request check ---------------------------------------------------------------------------

  @Test
  void aFreshSessionPassesWithOnlyARead() {
    stored(session(NOW.minusSeconds(30), null, UserStatus.ACTIVE));

    SessionService readOnly =
        new SessionService(
            sessions,
            tokens,
            AuthTestFixtures.SESSION,
            hasher,
            issuer,
            audit,
            AuthTestFixtures.PROPERTIES,
            tx,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(readOnly.isActive(sessionId, userId)).isTrue();
    verify(sessions, never()).touch(any(), any(), any());
    verifyNoInteractions(tx);
  }

  @Test
  void lastSeenIsWrittenOnceTheIntervalHasPassed() {
    stored(session(NOW.minusSeconds(60), null, UserStatus.ACTIVE));

    assertThat(service.isActive(sessionId, userId)).isTrue();

    verify(sessions).touch(sessionId, NOW, NOW.minusSeconds(60));
  }

  @Test
  void anUnknownRevokedForeignOrInactiveSessionIsRefused() {
    when(sessions.find(sessionId)).thenReturn(Optional.empty());
    assertThat(service.isActive(sessionId, userId)).isFalse();

    stored(session(NOW, NOW, UserStatus.ACTIVE));
    assertThat(service.isActive(sessionId, userId)).isFalse();

    stored(session(NOW, null, UserStatus.ACTIVE));
    assertThat(service.isActive(sessionId, UUID.randomUUID())).isFalse();

    stored(session(NOW, null, UserStatus.FROZEN));
    assertThat(service.isActive(sessionId, userId)).isFalse();
    verify(sessions, never()).revoke(any(), any(), any());
  }

  @Test
  void anIdleSessionIsRevokedOnceAndAudited() {
    stored(session(NOW.minus(IDLE), null, UserStatus.ACTIVE));
    when(sessions.revoke(sessionId, "IDLE_TIMEOUT", NOW)).thenReturn(true);

    assertThat(service.isActive(sessionId, userId)).isFalse();

    verify(tokens).revokeAll(sessionId, NOW);
    assertThat(audited().getFirst().newValue()).containsEntry("reason", "IDLE_TIMEOUT");
  }

  @Test
  void aSessionAnotherRequestJustRevokedIsNotAuditedTwice() {
    stored(session(NOW.minus(IDLE), null, UserStatus.ACTIVE));
    when(sessions.revoke(sessionId, "IDLE_TIMEOUT", NOW)).thenReturn(false);

    assertThat(service.isActive(sessionId, userId)).isFalse();

    verifyNoInteractions(audit);
  }

  // Login ---------------------------------------------------------------------------------------

  @Test
  void openingStoresOnlyADigestOfAStrongRandomToken() {
    when(sessions.insert(userId, NOW)).thenReturn(sessionId);

    SessionService.OpenedSession opened = service.open(userId, NOW);

    assertThat(Base64.getUrlDecoder().decode(opened.refreshToken())).hasSize(32);
    verify(tokens)
        .insert(
            sessionId,
            hasher.refreshToken(opened.refreshToken()),
            NOW.plus(AuthTestFixtures.PROPERTIES.token().refreshTtl()));
    assertThat(opened.toString()).doesNotContain(opened.refreshToken());
  }

  @Test
  void issuedTokensComeFromTheConfigurationAndHideTheirValues() {
    IssuedTokens issued =
        service.tokens(
            userId, Role.CUSTOMER, new SessionService.OpenedSession(sessionId, "refresh"), NOW);

    assertThat(issued.expiresInSeconds()).isEqualTo(900);
    assertThat(issued.refreshMaxAge()).isEqualTo(Duration.ofDays(7));
    assertThat(issued.accessToken().split("\\.")).hasSize(3);
    assertThat(issued.toString()).doesNotContain("refresh=").doesNotContain(issued.accessToken());
  }

  // Refresh -------------------------------------------------------------------------------------

  @Test
  void aMissingOrBlankTokenIsRefusedWithoutAQuery() {
    for (String token : new String[] {null, " "}) {
      assertThatThrownBy(() -> service.refresh(token, null)).isInstanceOf(ApiException.class);
    }
    verifyNoInteractions(tokens);
  }

  @Test
  void anUnknownTokenIsRefused() {
    when(tokens.findSessionId(any())).thenReturn(Optional.empty());
    assertRefreshRefused("UNKNOWN_TOKEN");
  }

  @Test
  void aValidTokenRotates() {
    storedToken(NOW.plusSeconds(60), null, null);
    stored(session(NOW, null, UserStatus.ACTIVE));

    IssuedTokens issued = service.refresh(TOKEN, null);

    verify(tokens).markUsed(any(), eq(NOW));
    verify(sessions).touch(sessionId, NOW, NOW);
    verify(tokens).insert(eq(sessionId), eq(hasher.refreshToken(issued.refreshToken())), any());
    assertThat(issued.refreshToken()).isNotEqualTo(TOKEN);
  }

  @Test
  void aUsedTokenRevokesTheFamily() {
    storedToken(NOW.plusSeconds(60), NOW.minusSeconds(1), null);
    stored(session(NOW, null, UserStatus.ACTIVE));
    when(sessions.revoke(sessionId, "TOKEN_REUSE", NOW)).thenReturn(true);

    assertRefreshRefused("TOKEN_REUSE");

    verify(tokens).revokeAll(sessionId, NOW);
    verify(tokens, never()).insert(any(), any(), any());
    assertThat(audited()).extracting(AuditEntry::action).contains("SESSION_REVOKED");
  }

  @Test
  void aUsedTokenOnAnAlreadyRevokedSessionIsNotAuditedAsANewRevocation() {
    storedToken(NOW.plusSeconds(60), NOW.minusSeconds(1), null);
    stored(session(NOW, NOW, UserStatus.ACTIVE));
    when(sessions.revoke(sessionId, "TOKEN_REUSE", NOW)).thenReturn(false);

    assertRefreshRefused("TOKEN_REUSE");

    assertThat(audited()).extracting(AuditEntry::action).doesNotContain("SESSION_REVOKED");
  }

  @Test
  void aRevokedTokenIsRefusedWithoutANewRevocation() {
    storedToken(NOW.plusSeconds(60), NOW.minusSeconds(2), NOW.minusSeconds(1));
    stored(session(NOW, NOW, UserStatus.ACTIVE));

    assertRefreshRefused("REVOKED_TOKEN");

    verify(sessions, never()).revoke(any(), any(), any());
  }

  @Test
  void anExpiredTokenIsRefusedAtItsInstant() {
    storedToken(NOW, null, null);
    stored(session(NOW, null, UserStatus.ACTIVE));
    assertRefreshRefused("EXPIRED_TOKEN");
  }

  @Test
  void aRevokedSessionIsRefused() {
    storedToken(NOW.plusSeconds(60), null, null);
    stored(session(NOW, NOW, UserStatus.ACTIVE));
    assertRefreshRefused("REVOKED_SESSION");
  }

  @Test
  void anIdleSessionIsRevokedAndRefused() {
    storedToken(NOW.plusSeconds(60), null, null);
    stored(session(NOW.minus(IDLE), null, UserStatus.ACTIVE));
    when(sessions.revoke(sessionId, "IDLE_TIMEOUT", NOW)).thenReturn(true);

    assertRefreshRefused("IDLE_TIMEOUT");

    verify(tokens).revokeAll(sessionId, NOW);
  }

  @Test
  void aUserNoLongerActiveIsRefused() {
    storedToken(NOW.plusSeconds(60), null, null);
    stored(session(NOW, null, UserStatus.SUSPENDED));
    assertRefreshRefused("USER_NOT_ACTIVE");
    verify(tokens, never()).markUsed(any(), any());
  }

  @Test
  void theSessionIsLockedBeforeTheTokenAndTheTokenIsReadUnderItsLock() {
    storedToken(NOW.plusSeconds(60), null, null);
    stored(session(NOW, null, UserStatus.ACTIVE));

    service.refresh(TOKEN, null);

    String hash = hasher.refreshToken(TOKEN);
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(tokens, sessions);
    order.verify(tokens).findSessionId(hash);
    order.verify(sessions).findForUpdate(sessionId);
    order.verify(tokens).findForUpdate(hash);
    order.verify(tokens).markUsed(any(), eq(NOW));
    verify(sessions, never()).find(sessionId);
  }

  @Test
  void aSessionPurgedAfterTheUnlockedReadIsAnUnknownToken() {
    when(tokens.findSessionId(any())).thenReturn(Optional.of(sessionId));
    when(sessions.findForUpdate(sessionId)).thenReturn(Optional.empty());

    assertRefreshRefused("UNKNOWN_TOKEN");

    verify(tokens, never()).findForUpdate(any());
  }

  @Test
  void aTokenPurgedAfterTheUnlockedReadIsAnUnknownToken() {
    when(tokens.findSessionId(any())).thenReturn(Optional.of(sessionId));
    stored(session(NOW, null, UserStatus.ACTIVE));
    when(tokens.findForUpdate(any())).thenReturn(Optional.empty());

    assertRefreshRefused("UNKNOWN_TOKEN");
  }

  // Staff ---------------------------------------------------------------------------------------

  private void storedAs(Role role, Instant lastSeen) {
    stored(new SessionRow(sessionId, userId, lastSeen, null, UserStatus.ACTIVE, role));
  }

  private void assertAuditedAsStaff(String action) {
    AuditEntry entry =
        audited().stream().filter(row -> row.action().equals(action)).findFirst().orElseThrow();
    assertThat(entry.actorType().name()).isEqualTo("STAFF");
    assertThat(entry.actorId()).isEqualTo(userId);
  }

  @Test
  void aStaffRefreshIsAuditedAsStaff() {
    storedToken(NOW.plusSeconds(60), null, null);
    storedAs(Role.ADMIN, NOW);

    service.refresh(TOKEN, null);

    assertAuditedAsStaff("TOKEN_REFRESH");
  }

  @Test
  void aStaffLogoutIsAuditedAsStaff() {
    storedAs(Role.COMPLIANCE_OFFICER, NOW);

    service.logout(sessionId, userId, null);

    assertAuditedAsStaff("LOGOUT");
  }

  @Test
  void aStaffIdleRevocationIsAuditedAsStaff() {
    storedAs(Role.ADMIN, NOW.minus(IDLE));
    when(sessions.revoke(sessionId, "IDLE_TIMEOUT", NOW)).thenReturn(true);

    assertThat(service.isActive(sessionId, userId)).isFalse();

    assertAuditedAsStaff("SESSION_REVOKED");
  }

  @Test
  void anIdleRevocationByARefusedRefreshNamesTheOwnerButNotAsTheActor() {
    storedToken(NOW.plusSeconds(60), null, null);
    storedAs(Role.ADMIN, NOW.minus(IDLE));
    when(sessions.revoke(sessionId, "IDLE_TIMEOUT", NOW)).thenReturn(true);

    assertRefreshRefused("IDLE_TIMEOUT");

    AuditEntry revoked = auditedAs("SESSION_REVOKED");
    assertThat(revoked.actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(revoked.actorId()).isNull();
    assertThat(revoked.resourceId()).isEqualTo(sessionId.toString());
    assertThat(revoked.newValue())
        .containsEntry("reason", "IDLE_TIMEOUT")
        .containsEntry("ownerId", userId.toString());
    verify(tokens).revokeAll(sessionId, NOW);
    assertAnonymousNamingTheOwner(auditedAs("TOKEN_REFRESH"));
  }

  @Test
  void aReusedTokenNamesItsOwnerButNotAsTheActorWhateverTheRole() {
    storedToken(NOW.plusSeconds(60), NOW.minusSeconds(1), null);
    storedAs(Role.ADMIN, NOW);
    when(sessions.revoke(sessionId, "TOKEN_REUSE", NOW)).thenReturn(true);

    assertRefreshRefused("TOKEN_REUSE");

    AuditEntry revoked = auditedAs("SESSION_REVOKED");
    assertThat(revoked.actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(revoked.actorId()).isNull();
    assertThat(revoked.resourceId()).isEqualTo(sessionId.toString());
    assertThat(revoked.newValue()).containsEntry("ownerId", userId.toString());
    assertAnonymousNamingTheOwner(auditedAs("TOKEN_REFRESH"));
  }

  @Test
  void aRefusedRefreshNamesTheOwnerButNotAsTheActor() {
    storedToken(NOW, null, null);
    storedAs(Role.ADMIN, NOW);

    assertRefreshRefused("EXPIRED_TOKEN");

    assertAnonymousNamingTheOwner(auditedAs("TOKEN_REFRESH"));
  }

  @Test
  void anUnknownTokenHasNoOwnerToName() {
    when(tokens.findSessionId(any())).thenReturn(Optional.empty());

    assertRefreshRefused("UNKNOWN_TOKEN");

    assertThat(auditedAs("TOKEN_REFRESH").newValue()).doesNotContainKey("ownerId");
  }

  private AuditEntry auditedAs(String action) {
    return audited().stream().filter(row -> row.action().equals(action)).findFirst().orElseThrow();
  }

  private void assertAnonymousNamingTheOwner(AuditEntry entry) {
    assertThat(entry.actorType()).isEqualTo(ActorType.ANONYMOUS);
    assertThat(entry.actorId()).isNull();
    assertThat(entry.newValue()).containsEntry("ownerId", userId.toString());
  }

  // Logout --------------------------------------------------------------------------------------

  @Test
  void logoutRevokesTheSessionAndItsTokensAndIsAudited() {
    service.logout(sessionId, userId, null);

    verify(sessions).revoke(sessionId, "LOGOUT", NOW);
    verify(tokens).revokeAll(sessionId, NOW);
    assertThat(audited().getFirst().action()).isEqualTo("LOGOUT");
    assertThat(audited().getFirst().actorId()).isEqualTo(userId);
  }
}
