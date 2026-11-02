package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.NOT_NULL_VIOLATION;
import static com.portfolio.wallet.support.SqlAssertions.assertCheckViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertForeignKeyViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertPermissionDenied;
import static com.portfolio.wallet.support.SqlAssertions.assertSqlState;
import static com.portfolio.wallet.support.SqlAssertions.assertUniqueViolation;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * The {@code auth} schema (documentations/08 §4A.3; doc 06 MVP-ADR-23): constraints, unique rules,
 * column-level UPDATE grants and server-set timestamps. Every statement runs as {@code wallet_app}.
 */
class AuthConstraintsIT extends DatabaseIntegrationTest {

  private static final String ARGON2_HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";

  private static final OffsetDateTime SENT_AT = OffsetDateTime.parse("2030-01-01T00:00:00Z");

  // Fixtures ----------------------------------------------------------------------------------

  private UUID newCredentialUser() throws SQLException {
    UUID user = newUser();
    insert("auth.credentials", validCredentials(user));
    return user;
  }

  private static Map<String, Object> validCredentials(UUID user) {
    return row(
        "user_id", user, "password_hash", ARGON2_HASH, "password_changed_at", OffsetDateTime.now());
  }

  private UUID newSession() throws SQLException {
    UUID id = UUID.randomUUID();
    insert(
        "auth.sessions", row("id", id, "user_id", newUser(), "last_seen_at", OffsetDateTime.now()));
    return id;
  }

  private Map<String, Object> validRefreshToken(UUID session) {
    return row(
        "id",
        UUID.randomUUID(),
        "session_id",
        session,
        "token_hash",
        randomHexDigest(),
        "expires_at",
        OffsetDateTime.now().plusDays(7));
  }

  private static Map<String, Object> validChallenge(String identifierHash) {
    return row(
        "id",
        UUID.randomUUID(),
        "identifier_hash",
        identifierHash,
        "purpose",
        "REGISTRATION",
        "otp_hash",
        randomHexDigest(),
        "sent_at",
        SENT_AT,
        "expires_at",
        SENT_AT.plusMinutes(5));
  }

  private Map<String, Object> validResetToken(UUID user) {
    return row(
        "id",
        UUID.randomUUID(),
        "user_id",
        user,
        "token_hash",
        randomHexDigest(),
        "expires_at",
        OffsetDateTime.now().plusMinutes(15));
  }

  private OffsetDateTime readTime(String sql, Object key) {
    return jdbc.queryForObject(sql, OffsetDateTime.class, key);
  }

  // Schema-wide -------------------------------------------------------------------------------

  /** Any column about a secret holds a hash or a time; no other such column may appear. */
  @Test
  void noColumnCanHoldAPlaintextSecret() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT DISTINCT column_name FROM information_schema.columns"
                + " WHERE table_schema = 'auth' AND (column_name LIKE '%password%'"
                + " OR column_name LIKE '%otp%' OR column_name LIKE '%token%'"
                + " OR column_name LIKE '%secret%')",
            String.class);

    assertThat(columns)
        .containsExactlyInAnyOrder(
            "password_hash", "password_changed_at", "otp_hash", "token_hash");
  }

  @Test
  void theMigrationSeedsNoAccount() throws IOException {
    String migration =
        new ClassPathResource("db/migration/V7__auth_create_auth.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(migration).doesNotContainIgnoringCase("INSERT INTO");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "auth.users",
        "auth.credentials",
        "auth.password_history",
        "auth.login_failure_counters",
        "auth.sessions",
        "auth.refresh_tokens",
        "auth.otp_challenges",
        "auth.password_reset_tokens",
        "auth.owner_notices"
      })
  void runtimeRoleCannotTruncate(String table) {
    assertPermissionDenied(() -> jdbc.execute("TRUNCATE " + table + " CASCADE"));
  }

  // Deleting a user ------------------------------------------------------------------------------

  private UUID userWithStatus(String status) throws SQLException {
    UUID id = newUser();
    jdbc.update("UPDATE auth.users SET status = ? WHERE id = ?", status, id);
    return id;
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "FROZEN", "SUSPENDED"})
  void theRuntimeRoleCanOnlyDeleteAPendingUser(String status) throws SQLException {
    UUID id = userWithStatus(status);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> jdbc.update("DELETE FROM auth.users WHERE id = ?", id))
        .hasMessageContaining("only a PENDING_VERIFICATION account can be deleted");
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM auth.users WHERE id = ?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void theTriggerHoldsForTheOwnerToo() throws SQLException {
    UUID id = userWithStatus("ACTIVE");
    try (Connection owner = ownerDataSource().getConnection()) {
      owner.setAutoCommit(false);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> update(owner, "DELETE FROM auth.users WHERE id = ?", id))
          .hasMessageContaining("only a PENDING_VERIFICATION account can be deleted");
      owner.rollback();
    }
  }

  @Test
  void deletingAPendingUserTakesItsCredentialsWithItThoughTheRoleCannotDeleteThem()
      throws SQLException {
    UUID id = newUser();
    insert(
        "auth.credentials",
        row(
            "user_id",
            id,
            "password_hash",
            "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA",
            "password_changed_at",
            OffsetDateTime.now()));
    insert(
        "auth.password_history",
        row("user_id", id, "password_hash", "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA"));
    insert("auth.owner_notices", row("user_id", id, "last_sent_at", OffsetDateTime.now()));

    assertThat(jdbc.update("DELETE FROM auth.users WHERE id = ?", id)).isEqualTo(1);

    for (String table :
        new String[] {"auth.credentials", "auth.password_history", "auth.owner_notices"}) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, id))
          .isZero();
    }
  }

  @Test
  void aPendingUserWithASessionCannotBeDeleted() throws SQLException {
    UUID id = newUser();
    insert("auth.sessions", row("user_id", id, "last_seen_at", OffsetDateTime.now()));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> jdbc.update("DELETE FROM auth.users WHERE id = ?", id))
        .hasMessageContaining("fk_sessions_user");
  }

  @Test
  void theRuntimeRoleCannotDeleteOwnerNotices() throws SQLException {
    UUID id = newUser();
    insert("auth.owner_notices", row("user_id", id, "last_sent_at", OffsetDateTime.now()));

    assertPermissionDenied(
        () -> jdbc.update("DELETE FROM auth.owner_notices WHERE user_id = ?", id));
  }

  static Stream<Arguments> columnsThatCannotBeUpdated() {
    return Stream.of(
        Arguments.of("auth.users", "id"),
        Arguments.of("auth.users", "role"),
        Arguments.of("auth.users", "created_at"),
        Arguments.of("auth.users", "updated_at"),
        Arguments.of("auth.credentials", "user_id"),
        Arguments.of("auth.credentials", "updated_at"),
        Arguments.of("auth.password_history", "password_hash"),
        Arguments.of("auth.login_failure_counters", "identifier_hash"),
        Arguments.of("auth.sessions", "user_id"),
        Arguments.of("auth.sessions", "created_at"),
        Arguments.of("auth.refresh_tokens", "session_id"),
        Arguments.of("auth.refresh_tokens", "token_hash"),
        Arguments.of("auth.refresh_tokens", "expires_at"),
        Arguments.of("auth.otp_challenges", "otp_hash"),
        Arguments.of("auth.otp_challenges", "expires_at"),
        Arguments.of("auth.password_reset_tokens", "token_hash"),
        Arguments.of("auth.password_reset_tokens", "expires_at"));
  }

  @ParameterizedTest(name = "{0}.{1}")
  @MethodSource("columnsThatCannotBeUpdated")
  void runtimeRoleCannotUpdateIdentityOrHashColumns(String table, String column) {
    assertPermissionDenied(
        () -> jdbc.update("UPDATE " + table + " SET " + column + " = " + column));
  }

  // Users -------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"Upper@example.test", "no-at-sign.example.test", "@example.test", "a@"})
  void nonCanonicalEmailIsRejected(String email) {
    assertCheckViolation(
        "ck_users_email", () -> insert("auth.users", with(validUser(), "email", email)));
  }

  @Test
  void emailIsUnique() throws SQLException {
    Map<String, Object> first = validUser();
    insert("auth.users", first);

    assertUniqueViolation(
        "uq_users_email",
        () -> insert("auth.users", with(validUser(), "email", first.get("email"))));
  }

  @Test
  void phoneNumberIsUnique() throws SQLException {
    Map<String, Object> first = validUser();
    insert("auth.users", first);

    assertUniqueViolation(
        "uq_users_phone_number",
        () -> insert("auth.users", with(validUser(), "phone_number", first.get("phone_number"))));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"0812345678", "+1234567", "+66 812345678", "+66812345678\n"})
  void phoneNumberMustMatchE164(String phone) {
    assertCheckViolation(
        "ck_users_phone_number",
        () -> insert("auth.users", with(validUser(), "phone_number", phone)));
  }

  @Test
  void phoneNumberWithSixteenDigitsDoesNotFitTheColumn() {
    assertSqlState(
        "22001",
        () -> insert("auth.users", with(validUser(), "phone_number", "+1234567890123456")));
  }

  @ParameterizedTest(name = "{0} digits")
  @ValueSource(ints = {8, 15})
  void phoneNumberLengthBoundariesAreAccepted(int digits) throws SQLException {
    String number = randomDigits(digits);

    insert("auth.users", with(validUser(), "phone_number", "+" + number));
  }

  @Test
  void customerRequiresAPhoneNumber() {
    assertCheckViolation(
        "ck_users_customer_phone",
        () -> insert("auth.users", with(validUser(), "phone_number", null)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffMayHaveNoPhoneNumber(String role) throws SQLException {
    Map<String, Object> staff = with(with(validUser(), "role", role), "phone_number", null);
    insert("auth.users", staff);
    insert("auth.users", with(with(validUser(), "role", role), "phone_number", null));
  }

  @Test
  void unknownRoleIsRejected() {
    assertCheckViolation(
        "ck_users_role", () -> insert("auth.users", with(validUser(), "role", "MERCHANT")));
  }

  @Test
  void roleIsRequired() {
    Map<String, Object> user = validUser();
    user.remove("role");

    assertSqlState(NOT_NULL_VIOLATION, () -> insert("auth.users", user));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"PENDING_VERIFICATION", "ACTIVE", "FROZEN", "SUSPENDED"})
  void documentedStatusIsAccepted(String status) throws SQLException {
    insert("auth.users", with(validUser(), "status", status));
  }

  @Test
  void unknownStatusIsRejected() {
    assertCheckViolation(
        "ck_users_status", () -> insert("auth.users", with(validUser(), "status", "CLOSED")));
  }

  @Test
  void newUserIsPendingVerificationByDefault() throws SQLException {
    UUID user = newUser();

    assertThat(
            jdbc.queryForObject("SELECT status FROM auth.users WHERE id = ?", String.class, user))
        .isEqualTo("PENDING_VERIFICATION");
  }

  @Test
  void clientSuppliedUserTimestampsAreOverwritten() throws SQLException {
    Map<String, Object> user =
        with(with(validUser(), "created_at", BACKDATED), "updated_at", BACKDATED);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, "auth.users", user);
        });

    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT created_at = ? AS created, updated_at = ? AS updated FROM auth.users"
                + " WHERE id = ?",
            now[0],
            now[0],
            user.get("id"));
    assertThat(stored.values()).containsOnly(true);
  }

  @Test
  void updatingAUserSetsUpdatedAtFromTheServer() throws SQLException {
    UUID user = newUser();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          queryOne(c, "SELECT 1 FROM pg_sleep(0.01)", Integer.class);
          now[0] = transactionNow(c);
          update(c, "UPDATE auth.users SET status = 'ACTIVE' WHERE id = ?", user);
        });

    assertThat(readTime("SELECT updated_at FROM auth.users WHERE id = ?", user)).isEqualTo(now[0]);
    assertThat(readTime("SELECT created_at FROM auth.users WHERE id = ?", user)).isBefore(now[0]);
  }

  // Credentials -------------------------------------------------------------------------------

  @Test
  void credentialsRequireAUser() {
    assertForeignKeyViolation(
        "fk_credentials_user",
        () -> insert("auth.credentials", validCredentials(UUID.randomUUID())));
  }

  @Test
  void aUserHasOneCredentialRow() throws SQLException {
    UUID user = newCredentialUser();

    assertUniqueViolation(
        "pk_credentials", () -> insert("auth.credentials", validCredentials(user)));
  }

  @Test
  void credentialsAcceptOnlyAnArgon2idHash() throws SQLException {
    UUID user = newUser();

    assertCheckViolation(
        "ck_credentials_password_hash",
        () ->
            insert(
                "auth.credentials",
                with(validCredentials(user), "password_hash", "plaintext-password")));
  }

  @Test
  void failedLoginCountCannotBeNegative() throws SQLException {
    UUID user = newCredentialUser();

    assertCheckViolation(
        "ck_credentials_failed_login_count",
        () ->
            jdbc.update(
                "UPDATE auth.credentials SET failed_login_count = -1 WHERE user_id = ?", user));
  }

  @Test
  void lockoutFieldsAndPasswordCanBeUpdatedAndUpdatedAtIsServerSet() throws SQLException {
    UUID user = newCredentialUser();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          queryOne(c, "SELECT 1 FROM pg_sleep(0.01)", Integer.class);
          now[0] = transactionNow(c);
          update(
              c,
              "UPDATE auth.credentials SET failed_login_count = 5, locked_until = ?,"
                  + " password_hash = ?, password_changed_at = ? WHERE user_id = ?",
              SENT_AT,
              ARGON2_HASH,
              SENT_AT,
              user);
        });

    assertThat(readTime("SELECT updated_at FROM auth.credentials WHERE user_id = ?", user))
        .isEqualTo(now[0]);
  }

  // Password history --------------------------------------------------------------------------

  @Test
  void passwordHistoryRequiresAUserAndAHash() throws SQLException {
    UUID user = newUser();

    assertForeignKeyViolation(
        "fk_password_history_user",
        () ->
            insert(
                "auth.password_history",
                row("user_id", UUID.randomUUID(), "password_hash", ARGON2_HASH)));
    assertCheckViolation(
        "ck_password_history_password_hash",
        () ->
            insert(
                "auth.password_history", row("user_id", user, "password_hash", "plain-password")));
  }

  @Test
  void passwordHistoryCreatedAtIsServerSetAndOldRowsCanBeDeleted() throws SQLException {
    UUID user = newUser();
    UUID id = UUID.randomUUID();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(
              c,
              "auth.password_history",
              row(
                  "id",
                  id,
                  "user_id",
                  user,
                  "password_hash",
                  ARGON2_HASH,
                  "created_at",
                  BACKDATED));
        });

    assertThat(readTime("SELECT created_at FROM auth.password_history WHERE id = ?", id))
        .isEqualTo(now[0]);
    assertThat(jdbc.update("DELETE FROM auth.password_history WHERE id = ?", id)).isEqualTo(1);
  }

  // Login failure counters --------------------------------------------------------------------

  @Test
  void counterIdentifierMustBeAHexDigestAndCountNonNegative() {
    assertCheckViolation(
        "ck_login_failure_counters_identifier_hash",
        () -> insert("auth.login_failure_counters", row("identifier_hash", "victim@example.test")));
    assertCheckViolation(
        "ck_login_failure_counters_failed_count",
        () ->
            insert(
                "auth.login_failure_counters",
                row("identifier_hash", randomHexDigest(), "failed_count", -1)));
  }

  @Test
  void counterCanBeUpsertedAndPurgedAndUpdatedAtIsServerSet() throws SQLException {
    String identifier = randomHexDigest();
    insert(
        "auth.login_failure_counters",
        row("identifier_hash", identifier, "failed_count", 1, "updated_at", BACKDATED));

    assertThat(
            readTime(
                "SELECT updated_at FROM auth.login_failure_counters WHERE identifier_hash = ?",
                identifier))
        .isAfter(BACKDATED);
    assertThat(
            jdbc.update(
                "INSERT INTO auth.login_failure_counters (identifier_hash, failed_count)"
                    + " VALUES (?, 1) ON CONFLICT (identifier_hash) DO UPDATE"
                    + " SET failed_count = auth.login_failure_counters.failed_count + 1",
                identifier))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT failed_count FROM auth.login_failure_counters WHERE identifier_hash = ?",
                Integer.class,
                identifier))
        .isEqualTo(2);
    assertThat(
            jdbc.update(
                "DELETE FROM auth.login_failure_counters WHERE identifier_hash = ?", identifier))
        .isEqualTo(1);
  }

  // Sessions ----------------------------------------------------------------------------------

  @Test
  void sessionRequiresAUser() {
    assertForeignKeyViolation(
        "fk_sessions_user",
        () ->
            insert(
                "auth.sessions",
                row("user_id", UUID.randomUUID(), "last_seen_at", OffsetDateTime.now())));
  }

  @Test
  void revokingASessionRequiresTimeAndReasonTogether() throws SQLException {
    UUID session = newSession();

    assertCheckViolation(
        "ck_sessions_revoked",
        () -> jdbc.update("UPDATE auth.sessions SET revoked_at = now() WHERE id = ?", session));
    assertCheckViolation(
        "ck_sessions_revoked",
        () ->
            jdbc.update(
                "UPDATE auth.sessions SET revoked_reason = 'LOGOUT' WHERE id = ?", session));
    assertCheckViolation(
        "ck_sessions_revoked_reason",
        () ->
            jdbc.update(
                "UPDATE auth.sessions SET revoked_at = now(), revoked_reason = 'BECAUSE'"
                    + " WHERE id = ?",
                session));
  }

  @Test
  void sessionTimesAreServerSetExceptLastSeen() throws SQLException {
    UUID user = newUser();
    UUID id = UUID.randomUUID();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(
              c,
              "auth.sessions",
              row("id", id, "user_id", user, "created_at", BACKDATED, "last_seen_at", SENT_AT));
        });

    assertThat(readTime("SELECT created_at FROM auth.sessions WHERE id = ?", id)).isEqualTo(now[0]);
    assertThat(readTime("SELECT last_seen_at FROM auth.sessions WHERE id = ?", id))
        .isEqualTo(SENT_AT);
    assertThat(
            jdbc.update(
                "UPDATE auth.sessions SET last_seen_at = ?, revoked_at = now(),"
                    + " revoked_reason = 'LOGOUT' WHERE id = ?",
                SENT_AT.plusMinutes(1),
                id))
        .isEqualTo(1);
  }

  // Refresh tokens ----------------------------------------------------------------------------

  @Test
  void refreshTokenHashIsUniqueAndAHexDigest() throws SQLException {
    Map<String, Object> first = validRefreshToken(newSession());
    insert("auth.refresh_tokens", first);
    UUID other = newSession();

    assertUniqueViolation(
        "uq_refresh_tokens_token_hash",
        () ->
            insert(
                "auth.refresh_tokens",
                with(validRefreshToken(other), "token_hash", first.get("token_hash"))));
    assertCheckViolation(
        "ck_refresh_tokens_token_hash",
        () ->
            insert(
                "auth.refresh_tokens",
                with(validRefreshToken(other), "token_hash", "raw-refresh-token")));
  }

  @Test
  void refreshTokenRequiresASession() {
    assertForeignKeyViolation(
        "fk_refresh_tokens_session",
        () -> insert("auth.refresh_tokens", validRefreshToken(UUID.randomUUID())));
  }

  @Test
  void refreshTokenCanBeMarkedUsedAndCreatedAtIsServerSet() throws SQLException {
    Map<String, Object> token = with(validRefreshToken(newSession()), "created_at", BACKDATED);
    insert("auth.refresh_tokens", token);

    assertThat(readTime("SELECT created_at FROM auth.refresh_tokens WHERE id = ?", token.get("id")))
        .isAfter(BACKDATED);
    assertThat(
            jdbc.update(
                "UPDATE auth.refresh_tokens SET used_at = now(), revoked_at = now() WHERE id = ?",
                token.get("id")))
        .isEqualTo(1);
  }

  @Test
  void purgingASessionRemovesItsTokens() throws SQLException {
    UUID session = newSession();
    insert("auth.refresh_tokens", validRefreshToken(session));

    assertThat(jdbc.update("DELETE FROM auth.sessions WHERE id = ?", session)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.refresh_tokens WHERE session_id = ?",
                Integer.class,
                session))
        .isZero();
  }

  // OTP challenges ----------------------------------------------------------------------------

  static Stream<Arguments> invalidChallengeColumns() {
    return Stream.of(
        Arguments.of("purpose", "LOGIN", "ck_otp_challenges_purpose"),
        Arguments.of("otp_hash", "123456", "ck_otp_challenges_otp_hash"),
        Arguments.of("identifier_hash", "victim@example.test", "ck_otp_challenges_identifier_hash"),
        Arguments.of("attempts", -1, "ck_otp_challenges_attempts"),
        Arguments.of("expires_at", SENT_AT, "ck_otp_challenges_expiry"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidChallengeColumns")
  void challengeChecksRejectInvalidValues(String column, Object value, String constraint) {
    assertCheckViolation(
        constraint,
        () ->
            insert("auth.otp_challenges", with(validChallenge(randomHexDigest()), column, value)));
  }

  @Test
  void passwordResetIsAValidPurpose() throws SQLException {
    insert(
        "auth.otp_challenges",
        with(validChallenge(randomHexDigest()), "purpose", "PASSWORD_RESET"));
  }

  @Test
  void onlyOneLiveChallengePerIdentifierAndPurpose() throws SQLException {
    String identifier = randomHexDigest();
    insert("auth.otp_challenges", validChallenge(identifier));

    assertUniqueViolation(
        "uq_otp_challenges_live", () -> insert("auth.otp_challenges", validChallenge(identifier)));
    // The other purpose is a separate challenge.
    insert("auth.otp_challenges", with(validChallenge(identifier), "purpose", "PASSWORD_RESET"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"consumed_at", "cancelled_at", "used_up_at"})
  void aChallengeThatEndedAllowsANewOne(String endColumn) throws SQLException {
    String identifier = randomHexDigest();
    Map<String, Object> first = validChallenge(identifier);
    insert("auth.otp_challenges", first);

    jdbc.update(
        "UPDATE auth.otp_challenges SET " + endColumn + " = now() WHERE id = ?", first.get("id"));

    insert("auth.otp_challenges", validChallenge(identifier));
  }

  @Test
  void aChallengeEndsInOnlyOneWay() throws SQLException {
    Map<String, Object> challenge = validChallenge(randomHexDigest());
    insert("auth.otp_challenges", challenge);

    assertCheckViolation(
        "ck_otp_challenges_one_end",
        () ->
            jdbc.update(
                "UPDATE auth.otp_challenges SET consumed_at = now(), cancelled_at = now()"
                    + " WHERE id = ?",
                challenge.get("id")));
  }

  @Test
  void attemptsCanBeCountedAndChallengesPurged() throws SQLException {
    Map<String, Object> challenge = validChallenge(randomHexDigest());
    insert("auth.otp_challenges", challenge);

    assertThat(
            jdbc.update(
                "UPDATE auth.otp_challenges SET attempts = attempts + 1 WHERE id = ?",
                challenge.get("id")))
        .isEqualTo(1);
    assertThat(jdbc.update("DELETE FROM auth.otp_challenges WHERE id = ?", challenge.get("id")))
        .isEqualTo(1);
  }

  // Password reset tokens ---------------------------------------------------------------------

  @Test
  void resetTokenHashIsUniqueAndAHexDigest() throws SQLException {
    UUID user = newUser();
    Map<String, Object> first = validResetToken(user);
    insert("auth.password_reset_tokens", first);

    assertUniqueViolation(
        "uq_password_reset_tokens_token_hash",
        () ->
            insert(
                "auth.password_reset_tokens",
                with(validResetToken(user), "token_hash", first.get("token_hash"))));
    assertCheckViolation(
        "ck_password_reset_tokens_token_hash",
        () ->
            insert(
                "auth.password_reset_tokens",
                with(validResetToken(user), "token_hash", "raw-reset-token")));
  }

  @Test
  void resetTokenRequiresAUser() {
    assertForeignKeyViolation(
        "fk_password_reset_tokens_user",
        () -> insert("auth.password_reset_tokens", validResetToken(UUID.randomUUID())));
  }

  @Test
  void resetTokenCreatedAtIsServerSetAndItCanBeUsedAndPurged() throws SQLException {
    Map<String, Object> token = with(validResetToken(newUser()), "created_at", BACKDATED);
    OffsetDateTime[] now = new OffsetDateTime[1];

    try (Connection c = openTransaction()) {
      now[0] = transactionNow(c);
      insert(c, "auth.password_reset_tokens", token);
      c.commit();
    }

    Object id = token.get("id");
    assertThat(readTime("SELECT created_at FROM auth.password_reset_tokens WHERE id = ?", id))
        .isEqualTo(now[0]);
    assertThat(
            jdbc.update("UPDATE auth.password_reset_tokens SET used_at = now() WHERE id = ?", id))
        .isEqualTo(1);
    assertThat(jdbc.update("DELETE FROM auth.password_reset_tokens WHERE id = ?", id)).isEqualTo(1);
  }
}
