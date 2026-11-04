package com.portfolio.wallet.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.portfolio.wallet.auth.application.AuthPurgeService;
import com.portfolio.wallet.auth.application.KeyedHasher;
import com.portfolio.wallet.auth.persistence.RefreshTokenRepository;
import com.portfolio.wallet.notification.application.EmailKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** The purge removes what has ended and keeps what a limit still needs (AC 5). */
class AuthPurgeIT extends AuthFlowSupport {

  private static final Duration DAY = Duration.ofHours(24);

  @Autowired AuthPurgeService purge;
  @Autowired KeyedHasher hasher;
  @Autowired RefreshTokenRepository refreshTokens;

  private OffsetDateTime ago(Duration duration) {
    return clock.instant().minus(duration).atOffset(ZoneOffset.UTC);
  }

  private OffsetDateTime ahead(Duration duration) {
    return clock.instant().plus(duration).atOffset(ZoneOffset.UTC);
  }

  private boolean counterExists(String hash) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM auth.login_failure_counters WHERE identifier_hash = ?",
            Integer.class,
            hash)
        == 1;
  }

  private boolean exists(String table, UUID id) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id)
        == 1;
  }

  /**
   * updated_at is set by a trigger, so an old value needs the trigger off. It is the database's
   * clock, whatever the application's says; the tests move only the latter.
   */
  private String counter(Duration age, OffsetDateTime lockedUntil) throws SQLException {
    String hash = randomHexDigest();
    insertWithTriggersDisabled(
        "auth.login_failure_counters",
        row(
            "identifier_hash",
            hash,
            "failed_count",
            1,
            "locked_until",
            lockedUntil,
            "updated_at",
            OffsetDateTime.now(ZoneOffset.UTC).minus(age)));
    return hash;
  }

  @Test
  void unknownIdentifierCountersGoAfter24HoursUnlessALockIsRunning() throws Exception {
    String fresh = counter(DAY.minusMinutes(1), null);
    String old = counter(DAY, null);
    String oldButLocked = counter(DAY.plusHours(1), ahead(DAY.multipliedBy(3)));
    // The application's clock has no say in the age of a row the database dated.
    clock.advance(DAY.multipliedBy(2));

    purge.purgeExpired();

    assertThat(counterExists(fresh)).isTrue();
    assertThat(counterExists(old)).isFalse();
    assertThat(counterExists(oldButLocked)).isTrue();
  }

  private UUID challenge(
      Duration sentAgo, Duration expiresAgo, String endColumn, OffsetDateTime endedAt)
      throws SQLException {
    UUID id = UUID.randomUUID();
    insert(
        "auth.otp_challenges",
        row(
            "id",
            id,
            "identifier_hash",
            randomHexDigest(),
            "purpose",
            "PASSWORD_RESET",
            "otp_hash",
            randomHexDigest(),
            "sent_at",
            ago(sentAgo),
            "expires_at",
            ago(expiresAgo),
            endColumn,
            endedAt));
    return id;
  }

  @Test
  void challengesStayUntilTheyAreOutsideTheLongestWindow() throws Exception {
    Duration overDay = DAY.plusMinutes(1);
    Duration expired = DAY;
    UUID oldConsumed = challenge(overDay, expired, "consumed_at", ago(overDay));
    UUID oldUsedUp = challenge(overDay, expired, "used_up_at", ago(overDay));
    // Sent long ago, but used up inside the 24-hour window: the cap still counts it.
    UUID usedUpInWindow = challenge(overDay, expired, "used_up_at", ago(DAY.minusMinutes(1)));
    UUID sentInWindow =
        challenge(
            DAY.minusMinutes(1), Duration.ofHours(23), "cancelled_at", ago(DAY.minusHours(2)));

    purge.purgeExpired();

    assertThat(exists("auth.otp_challenges", oldConsumed)).isFalse();
    assertThat(exists("auth.otp_challenges", oldUsedUp)).isFalse();
    assertThat(exists("auth.otp_challenges", usedUpInWindow)).isTrue();
    assertThat(exists("auth.otp_challenges", sentInWindow)).isTrue();
  }

  @Test
  void theUsedUpBlockStillHoldsAfterAPurge() throws Exception {
    String email = email();
    register(email, phone());
    String code = awaitOtp(email, 1);
    for (int challenge = 1; challenge <= 5; challenge++) {
      String current = challenge == 1 ? code : awaitOtp(email, challenge);
      for (int attempt = 0; attempt < 5; attempt++) {
        verify(email, "REGISTRATION", wrongCode(current));
      }
      clock.advance(Duration.ofHours(1));
      resend(email);
    }

    purge.purgeExpired();
    clock.advance(Duration.ofHours(1));
    resend(email);

    assertMailCountStays(email, EmailKind.OTP, 5);
  }

  @Test
  void theGuessCountStillHoldsAfterAPurge() throws Exception {
    String email = email();
    register(email, phone());
    // 25 wrong guesses, none of them using a challenge up: sent over more than the hourly cap.
    Duration between = Duration.ofMinutes(13);
    for (int challenge = 1; challenge <= 6; challenge++) {
      String code = awaitOtp(email, challenge);
      for (int attempt = 0; attempt < 4; attempt++) {
        verify(email, "REGISTRATION", wrongCode(code));
      }
      clock.advance(between);
      resend(email);
    }
    verify(email, "REGISTRATION", wrongCode(awaitOtp(email, 7)));

    purge.purgeExpired();
    clock.advance(between);
    resend(email);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'OTP_ISSUE'"
                    + " AND resource_id = ? AND new_value ->> 'reason' = 'USED_UP_BLOCK'",
                Integer.class,
                hasher.identifier(email)))
        .isEqualTo(1);
  }

  // Unverified accounts ---------------------------------------------------------------------------

  private void backdateCreation(String email, Duration age) throws SQLException {
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE auth.users SET created_at = now() - make_interval(secs => ?)"
                    + " WHERE email = ?",
                (double) age.toSeconds(),
                email));
  }

  private int count(String sql, Object arg) {
    return jdbc.queryForObject(sql, Integer.class, arg);
  }

  private UUID idOf(String email) {
    return jdbc.queryForObject("SELECT id FROM auth.users WHERE email = ?", UUID.class, email);
  }

  private void assertGone(String email, UUID id) {
    assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", email)).isZero();
    assertThat(count("SELECT count(*) FROM auth.credentials WHERE user_id = ?", id)).isZero();
    assertThat(count("SELECT count(*) FROM auth.password_history WHERE user_id = ?", id)).isZero();
    assertThat(count("SELECT count(*) FROM auth.owner_notices WHERE user_id = ?", id)).isZero();
  }

  @Test
  void anUnverifiedAccountGoesAtItsTtlWithItsDependentRowsAndOnlyThen() throws Exception {
    String old = email();
    String phone = phone();
    register(old, phone);
    // A notice is the third thing hanging off the row.
    register(email(), phone);
    awaitMailCount(old, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
    UUID oldId = idOf(old);
    backdateCreation(old, DAY);
    String fresh = email();
    register(fresh, phone());
    backdateCreation(fresh, DAY.minusMinutes(1));

    purge.purgeExpired();

    assertGone(old, oldId);
    assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", fresh)).isEqualTo(1);
  }

  @Test
  void aVerifiedOrFrozenAccountIsNeverPurgedHoweverOld() throws Exception {
    String active = activeUser();
    String frozen = activeUser();
    jdbc.update("UPDATE auth.users SET status = 'FROZEN' WHERE email = ?", frozen);
    backdateCreation(active, DAY.multipliedBy(30));
    backdateCreation(frozen, DAY.multipliedBy(30));

    purge.purgeExpired();

    assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", active)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", frozen)).isEqualTo(1);
  }

  @Test
  void aReRegistrationDoesNotExtendThePendingAccountsLifetime() throws Exception {
    String email = email();
    register(email, phone());
    awaitOtp(email, 1);
    backdateCreation(email, DAY);
    clock.advance(Duration.ofSeconds(61));
    register(email, phone());

    purge.purgeExpired();

    assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", email)).isZero();
  }

  @Test
  void registeringAfterThePurgeWorksNormally() throws Exception {
    String email = email();
    String phone = phone();
    register(email, phone);
    awaitOtp(email, 1);
    backdateCreation(email, DAY);
    purge.purgeExpired();
    clock.advance(Duration.ofSeconds(61));

    assertThat(register(email, phone).getStatus()).isEqualTo(201);

    assertThat(verify(email, "REGISTRATION", awaitOtp(email, 2)).getStatus()).isEqualTo(200);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void anAccountBeingVerifiedAsThePurgeRunsIsSkippedWithoutWaitingAndSurvives() throws Exception {
    String email = email();
    register(email, phone());
    backdateCreation(email, DAY);
    try (ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection verifying = openTransaction()) {
      update(verifying, "UPDATE auth.users SET status = 'ACTIVE' WHERE email = ?", email);

      // Finishes while the verification is still open, so it cannot have waited for it.
      pool.submit(purge::purgeExpired).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      verifying.commit();
    }
    purge.purgeExpired();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM auth.users WHERE email = ?", String.class, email))
        .isEqualTo("ACTIVE");
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }

  /** The purge against a registration holding each of the locks registering takes. */
  @ParameterizedTest
  @ValueSource(strings = {"FOR KEY SHARE", "FOR NO KEY UPDATE"})
  void aRowARegistrationHasLockedIsSkippedAndGoesOnTheNextRun(String lock) throws Exception {
    String locked = email();
    String other = email();
    register(locked, phone());
    register(other, phone());
    backdateCreation(locked, DAY);
    backdateCreation(other, DAY);
    UUID lockedId = idOf(locked);
    try (ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection registering = openTransaction()) {
      queryOne(registering, "SELECT id FROM auth.users WHERE id = ? " + lock, UUID.class, lockedId);

      pool.submit(purge::purgeExpired).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", locked)).isEqualTo(1);
      assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", other)).isZero();
      registering.commit();
    }

    purge.purgeExpired();

    assertGone(locked, lockedId);
  }

  private static int backendOf(Connection connection) throws SQLException {
    return queryOne(connection, "SELECT pg_backend_pid()", Integer.class);
  }

  /** Waits until some backend is blocked by this exact one, so the interleaving is in place. */
  private void awaitBlockedBy(int blocker) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(20))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity"
                            + " WHERE datname = current_database()"
                            + " AND ? = ANY (pg_blocking_pids(pid))",
                        Integer.class,
                        blocker)
                    >= 1);
  }

  @Test
  void registeringWithThePhoneOfAnAccountBeingPurgedIsTheUsual201() throws Exception {
    String owner = email();
    String phone = phone();
    register(owner, phone);
    UUID ownerId = idOf(owner);
    backdateCreation(owner, DAY);
    String email = email();
    try (ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection purging = openTransaction()) {
      update(purging, "DELETE FROM auth.users WHERE id = ?", ownerId);

      Future<Integer> registering = pool.submit(() -> register(email, phone).getStatus());
      awaitBlockedBy(backendOf(purging));
      purging.commit();

      assertThat(registering.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(201);
    }
    assertGone(owner, ownerId);
    assertThat(
            jdbc.queryForObject(
                "SELECT phone_number FROM auth.users WHERE email = ?", String.class, email))
        .isEqualTo(phone);
  }

  /**
   * The purge starts after the insert saw the owner and before the notice: the race that once made
   * the notice's foreign key fail. A lock on the registering email's row holds it in that gap.
   */
  @Test
  void anOwnerPurgedBetweenTheConflictAndTheNoticeIsNoOwnerAndTheAnswerIsThe201() throws Exception {
    String owner = email();
    String phone = phone();
    register(owner, phone);
    UUID ownerId = idOf(owner);
    backdateCreation(owner, DAY);
    String email = email();
    register(email, phone());
    try (ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection holding = openTransaction();
        Connection purging = openTransaction()) {
      queryOne(
          holding,
          "SELECT id FROM auth.users WHERE id = ? FOR NO KEY UPDATE",
          UUID.class,
          idOf(email));

      Future<Integer> registering = pool.submit(() -> register(email, phone).getStatus());
      awaitBlockedBy(backendOf(holding));
      update(purging, "DELETE FROM auth.users WHERE id = ?", ownerId);
      holding.commit();
      awaitBlockedBy(backendOf(purging));
      purging.commit();

      assertThat(registering.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(201);
    }
    assertGone(owner, ownerId);
    // No owner left, so this falls to the path that replaces a pending account.
    assertThat(
            jdbc.queryForObject(
                "SELECT phone_number FROM auth.users WHERE email = ?", String.class, email))
        .isEqualTo(phone);
  }

  /** One stray row must not make every purge fail on its foreign key. */
  @Test
  void anAccountWithASessionOrResetTokenStaysAndTheOthersStillGo() throws Exception {
    String withSession = email();
    String withResetToken = email();
    String other = email();
    for (String email : List.of(withSession, withResetToken, other)) {
      register(email, phone());
      backdateCreation(email, DAY);
    }
    session(idOf(withSession), ago(Duration.ZERO), null);
    resetToken(idOf(withResetToken), ahead(DAY));

    purge.purgeExpired();

    assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", withSession)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", withResetToken))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM auth.users WHERE email = ?", other)).isZero();
  }

  // Revocation stays off expired refresh tokens ---------------------------------------------------

  private OffsetDateTime revokedAt(UUID token) {
    return jdbc.queryForObject(
        "SELECT revoked_at FROM auth.refresh_tokens WHERE id = ?", OffsetDateTime.class, token);
  }

  @Test
  void revokingSkipsExpiredTokensEvenWhileThePurgeHoldsThem() throws Exception {
    UUID user = newUser();
    UUID bySession = session(user, ago(Duration.ZERO), null);
    UUID byUser = session(user, ago(Duration.ZERO), null);
    UUID expiredBySession = refreshToken(bySession, ago(Duration.ofSeconds(1)));
    UUID expiredAtItsInstant = refreshToken(byUser, ago(Duration.ZERO));
    UUID liveBySession = refreshToken(bySession, ahead(DAY));
    UUID liveByUser = refreshToken(byUser, ahead(DAY));
    try (ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection purging = openTransaction()) {
      update(
          purging,
          "DELETE FROM auth.refresh_tokens WHERE id IN (?, ?)",
          expiredBySession,
          expiredAtItsInstant);

      pool.submit(
              () -> {
                refreshTokens.revokeAll(bySession, clock.instant());
                refreshTokens.revokeAllForUser(user, clock.instant());
              })
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      purging.rollback();
    }
    assertThat(revokedAt(expiredBySession)).isNull();
    assertThat(revokedAt(expiredAtItsInstant)).isNull();
    assertThat(revokedAt(liveBySession)).isNotNull();
    assertThat(revokedAt(liveByUser)).isNotNull();
  }

  @Test
  void aVerifyWaitingOnThePurgeIsAnOrdinaryRefusal() throws Exception {
    String email = email();
    register(email, phone());
    String code = awaitOtp(email, 1);
    UUID id = idOf(email);
    backdateCreation(email, DAY);
    try (ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection purging = openTransaction()) {
      update(purging, "DELETE FROM auth.users WHERE id = ?", id);

      Future<Integer> verifying =
          pool.submit(() -> verify(email, "REGISTRATION", code).getStatus());
      awaitLockWaiters(purging, 1);
      purging.commit();

      assertThat(verifying.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(400);
    }
    assertGone(email, id);
  }

  private UUID session(UUID user, OffsetDateTime lastSeen, OffsetDateTime revokedAt)
      throws SQLException {
    UUID id = UUID.randomUUID();
    insert(
        "auth.sessions",
        row(
            "id",
            id,
            "user_id",
            user,
            "last_seen_at",
            lastSeen,
            "revoked_at",
            revokedAt,
            "revoked_reason",
            revokedAt == null ? null : "LOGOUT"));
    return id;
  }

  private UUID refreshToken(UUID session, OffsetDateTime expiresAt) throws SQLException {
    UUID id = UUID.randomUUID();
    insert(
        "auth.refresh_tokens",
        row(
            "id",
            id,
            "session_id",
            session,
            "token_hash",
            randomHexDigest(),
            "expires_at",
            expiresAt));
    return id;
  }

  @Test
  void endedSessionsWaitForTheirLastRefreshTokenAndLiveOnesStay() throws Exception {
    UUID user = newUser();
    OffsetDateTime now = ago(Duration.ZERO);
    OffsetDateTime past = ago(Duration.ofMinutes(1));
    OffsetDateTime future = ahead(Duration.ofDays(1));

    UUID revokedDone = session(user, now, now);
    UUID revokedDoneToken = refreshToken(revokedDone, past);
    UUID revokedWaiting = session(user, now, now);
    UUID waitingToken = refreshToken(revokedWaiting, future);
    UUID idleDone = session(user, ago(Duration.ofMinutes(30)), null);
    UUID live = session(user, now, null);
    UUID liveExpired = refreshToken(live, past);
    UUID liveUnexpired = refreshToken(live, future);

    purge.purgeExpired();

    assertThat(exists("auth.sessions", revokedDone)).isFalse();
    assertThat(exists("auth.refresh_tokens", revokedDoneToken)).isFalse();
    assertThat(exists("auth.sessions", revokedWaiting)).isTrue();
    assertThat(exists("auth.refresh_tokens", waitingToken)).isTrue();
    assertThat(exists("auth.sessions", idleDone)).isFalse();
    assertThat(exists("auth.sessions", live)).isTrue();
    assertThat(exists("auth.refresh_tokens", liveExpired)).isFalse();
    assertThat(exists("auth.refresh_tokens", liveUnexpired)).isTrue();
  }

  private UUID resetToken(UUID user, OffsetDateTime expiresAt) throws SQLException {
    UUID id = UUID.randomUUID();
    insert(
        "auth.password_reset_tokens",
        row("id", id, "user_id", user, "token_hash", randomHexDigest(), "expires_at", expiresAt));
    return id;
  }

  @Test
  void resetTokensGoOnceExpired() throws Exception {
    UUID user = newUser();
    UUID expired = resetToken(user, ago(Duration.ofSeconds(1)));
    UUID valid = resetToken(user, ahead(Duration.ofMinutes(1)));

    purge.purgeExpired();

    assertThat(exists("auth.password_reset_tokens", expired)).isFalse();
    assertThat(exists("auth.password_reset_tokens", valid)).isTrue();
  }
}
