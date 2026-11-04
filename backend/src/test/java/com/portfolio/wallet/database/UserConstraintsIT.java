package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.NOT_NULL_VIOLATION;
import static com.portfolio.wallet.support.SqlAssertions.assertCheckViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertForeignKeyViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertPermissionDenied;
import static com.portfolio.wallet.support.SqlAssertions.assertRestrictViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertSqlState;
import static com.portfolio.wallet.support.SqlAssertions.assertUniqueViolation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * The {@code usr} schema (documentations/08 §4B, V8; doc 06 MVP-ADR-10 amendment): constraints, one
 * active application, the state guard, the locked legal name and profile, column grants and
 * server-set times. Every statement runs as {@code wallet_app} unless it says otherwise.
 */
class UserConstraintsIT extends DatabaseIntegrationTest {

  private static final String PROFILE_LOCKED =
      "the profile is locked by a submitted or approved KYC application";

  private OffsetDateTime readTime(String sql, Object key) {
    return jdbc.queryForObject(sql, OffsetDateTime.class, key);
  }

  private String status(UUID application) {
    return jdbc.queryForObject(
        "SELECT status FROM usr.kyc_applications WHERE id = ?", String.class, application);
  }

  private void asOwnerRolledBack(SqlWork work) throws SQLException {
    try (Connection owner = ownerDataSource().getConnection()) {
      owner.setAutoCommit(false);
      try {
        work.run(owner);
      } finally {
        owner.rollback();
        owner.setAutoCommit(true);
      }
    }
  }

  // Schema-wide -------------------------------------------------------------------------------

  @Test
  void theMigrationSeedsNothing() throws IOException {
    String migration =
        new ClassPathResource("db/migration/V8__user_create_customers_and_kyc.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(migration).doesNotContainIgnoringCase("INSERT INTO");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"usr.customers", "usr.kyc_applications"})
  void runtimeRoleCannotDeleteOrTruncate(String table) {
    assertPermissionDenied(() -> jdbc.update("DELETE FROM " + table));
    assertPermissionDenied(() -> jdbc.execute("TRUNCATE " + table + " CASCADE"));
  }

  static Stream<Arguments> columnsThatCannotBeUpdated() {
    return Stream.of(
        Arguments.of("usr.customers", "user_id"),
        Arguments.of("usr.customers", "created_at"),
        Arguments.of("usr.customers", "updated_at"),
        Arguments.of("usr.kyc_applications", "id"),
        Arguments.of("usr.kyc_applications", "customer_id"),
        Arguments.of("usr.kyc_applications", "date_of_birth"),
        Arguments.of("usr.kyc_applications", "country_code"),
        Arguments.of("usr.kyc_applications", "submitted_at"),
        Arguments.of("usr.kyc_applications", "decided_at"),
        Arguments.of("usr.kyc_applications", "created_at"),
        Arguments.of("usr.kyc_applications", "updated_at"));
  }

  @ParameterizedTest(name = "{0}.{1}")
  @MethodSource("columnsThatCannotBeUpdated")
  void runtimeRoleCannotUpdateIdentityOrServerSetColumns(String table, String column) {
    assertPermissionDenied(
        () -> jdbc.update("UPDATE " + table + " SET " + column + " = " + column));
  }

  // Customers ---------------------------------------------------------------------------------

  private static final String NAME_ONLY_BY_APPROVAL =
      "the legal name is set only by approving its application";

  /**
   * Approves {@code application} and copies its name ('Somchai Jaidee') into the profile in one
   * transaction, in the order the approval uses (J4).
   */
  private void approveAndCopyName(UUID customer, UUID application) throws SQLException {
    inTransaction(
        c -> {
          update(
              c,
              "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ? WHERE id = ?",
              UUID.randomUUID(),
              application);
          update(
              c,
              "UPDATE usr.customers SET first_name = 'Somchai', last_name = 'Jaidee'"
                  + " WHERE user_id = ?",
              customer);
        });
  }

  private UUID pendingApplication(UUID customer) throws SQLException {
    UUID application = newKycApplication(customer);
    moveKycApplication(application, "PENDING_REVIEW");
    return application;
  }

  @Test
  void aCustomerIsInsertedOnceAndIdempotently() throws SQLException {
    UUID id = newCustomerWithoutProfile();

    assertUniqueViolation("pk_customers", () -> insert("usr.customers", row("user_id", id)));
    assertThat(
            jdbc.update(
                "INSERT INTO usr.customers (user_id) VALUES (?) ON CONFLICT DO NOTHING", id))
        .isZero();
  }

  /** J4: the runtime role inserts the user id only; everything else is written under the guard. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"first_name", "last_name", "date_of_birth", "country_code", "created_at"})
  void theRuntimeRoleMayInsertTheUserIdOnly(String column) {
    Object value =
        switch (column) {
          case "date_of_birth" -> LocalDate.of(1990, 1, 1);
          case "country_code" -> "TH";
          case "created_at" -> BACKDATED;
          default -> "Somchai";
        };

    assertPermissionDenied(
        () -> insert("usr.customers", row("user_id", UUID.randomUUID(), column, value)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"th", "T", "T1", "12"})
  void countryCodeMustBeTwoUpperCaseLetters(String code) throws SQLException {
    UUID id = newCustomerWithoutProfile();

    assertCheckViolation(
        "ck_customers_country_code",
        () -> jdbc.update("UPDATE usr.customers SET country_code = ? WHERE user_id = ?", code, id));
  }

  @Test
  void countryCodeOfThreeLettersDoesNotFitTheColumn() throws SQLException {
    UUID id = newCustomerWithoutProfile();

    assertSqlState(
        "22001",
        () -> jdbc.update("UPDATE usr.customers SET country_code = 'THA' WHERE user_id = ?", id));
  }

  /** As the owner, whose INSERT is not limited by column, so the check constraints decide. */
  @Test
  void theLegalNameIsAPairAndNotBlank() throws SQLException {
    asOwnerRolledBack(
        owner ->
            assertCheckViolation(
                "ck_customers_legal_name_pair",
                () ->
                    insert(
                        owner,
                        "usr.customers",
                        row("user_id", UUID.randomUUID(), "first_name", "Somchai"))));
    asOwnerRolledBack(
        owner ->
            assertCheckViolation(
                "ck_customers_legal_name_not_blank",
                () ->
                    insert(
                        owner,
                        "usr.customers",
                        row("user_id", UUID.randomUUID(), "first_name", " ", "last_name", "J"))));
  }

  @Test
  void theProfileCanBeUpdatedBeforeApprovalAndUpdatedAtIsServerSet() throws SQLException {
    UUID id = newCustomerWithoutProfile();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          queryOne(c, "SELECT 1 FROM pg_sleep(0.01)", Integer.class);
          now[0] = transactionNow(c);
          update(
              c,
              "UPDATE usr.customers SET date_of_birth = ?, country_code = 'TH' WHERE user_id = ?",
              LocalDate.of(1990, 1, 1),
              id);
          update(c, "UPDATE usr.customers SET country_code = 'GB' WHERE user_id = ?", id);
        });

    assertThat(readTime("SELECT updated_at FROM usr.customers WHERE user_id = ?", id))
        .isEqualTo(now[0]);
    assertThat(readTime("SELECT created_at FROM usr.customers WHERE user_id = ?", id))
        .isBefore(now[0]);
  }

  /**
   * The runtime role cannot supply times at all (J4); for the owner the trigger overwrites them.
   */
  @Test
  void clientSuppliedCustomerTimestampsAreOverwritten() throws SQLException {
    UUID id = UUID.randomUUID();

    asOwnerRolledBack(
        owner -> {
          OffsetDateTime now = transactionNow(owner);
          insert(
              owner,
              "usr.customers",
              row("user_id", id, "created_at", BACKDATED, "updated_at", BACKDATED));
          assertThat(
                  queryOne(
                      owner,
                      "SELECT created_at FROM usr.customers WHERE user_id = ?",
                      OffsetDateTime.class,
                      id))
              .isEqualTo(now);
          assertThat(
                  queryOne(
                      owner,
                      "SELECT updated_at FROM usr.customers WHERE user_id = ?",
                      OffsetDateTime.class,
                      id))
              .isEqualTo(now);
        });
  }

  /** J4: the approval of the application carrying the name, in the same transaction, sets it. */
  @Test
  void theLegalNameIsSetByApprovingItsApplication() throws SQLException {
    UUID id = newCustomer();
    approveAndCopyName(id, pendingApplication(id));

    assertThat(
            jdbc.queryForMap(
                "SELECT first_name, last_name FROM usr.customers WHERE user_id = ?", id))
        .containsEntry("first_name", "Somchai")
        .containsEntry("last_name", "Jaidee");
  }

  /** J4: without that approval the name cannot be written, by any role. */
  @Test
  void theLegalNameCannotBeWrittenWithoutApprovingItsApplication() throws SQLException {
    UUID id = newCustomer();
    UUID application = pendingApplication(id);
    String setName =
        "UPDATE usr.customers SET first_name = 'Somchai', last_name = 'Jaidee' WHERE user_id = ?";

    // No approval at all, with the application still pending.
    assertRestrictViolation(NAME_ONLY_BY_APPROVAL, () -> jdbc.update(setName, id));
    asOwnerRolledBack(
        owner -> assertRestrictViolation(NAME_ONLY_BY_APPROVAL, () -> update(owner, setName, id)));
    assertThat(
            jdbc.queryForObject(
                "SELECT first_name FROM usr.customers WHERE user_id = ?", String.class, id))
        .isNull();
    assertThat(status(application)).isEqualTo("PENDING_REVIEW");
  }

  // N3: an approval without the name copy cannot commit --------------------------------------

  private static final String APPROVED_WITHOUT_NAME = "was approved without copying its legal name";

  @Test
  void anApprovalWithoutCopyingTheLegalNameFailsAtCommit() throws SQLException {
    UUID id = newCustomer();
    UUID application = pendingApplication(id);

    assertRestrictViolation(
        APPROVED_WITHOUT_NAME,
        () ->
            inTransaction(
                c ->
                    update(
                        c,
                        "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ?"
                            + " WHERE id = ?",
                        UUID.randomUUID(),
                        application)));
    assertThat(status(application)).as("rolled back at commit").isEqualTo("PENDING_REVIEW");
    // Also as one auto-committed statement, and for the owner.
    assertRestrictViolation(
        APPROVED_WITHOUT_NAME,
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ? WHERE id = ?",
                UUID.randomUUID(),
                application));
    try (Connection owner = ownerDataSource().getConnection()) {
      owner.setAutoCommit(false);
      update(
          owner,
          "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ? WHERE id = ?",
          UUID.randomUUID(),
          application);
      assertRestrictViolation(APPROVED_WITHOUT_NAME, owner::commit);
      owner.setAutoCommit(true);
    }
    assertThat(status(application)).isEqualTo("PENDING_REVIEW");
  }

  /** The check is deferred: it runs at commit, after the name copy that follows the approval. */
  @Test
  void anApprovalThatCopiesTheLegalNameCommits() throws SQLException {
    UUID id = newCustomer();
    UUID application = pendingApplication(id);

    approveAndCopyName(id, application);

    assertThat(status(application)).isEqualTo("APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_triggerdef(oid) FROM pg_trigger"
                    + " WHERE tgname = 'trg_kyc_applications_approved_legal_name'",
                String.class))
        .contains("CONSTRAINT TRIGGER", "DEFERRABLE INITIALLY DEFERRED");
  }

  /** A rejection needs no legal name. */
  @Test
  void aRejectionCommitsWithoutALegalName() throws SQLException {
    UUID id = newCustomer();
    UUID application = pendingApplication(id);

    moveKycApplication(application, "REJECTED");

    assertThat(status(application)).isEqualTo("REJECTED");
  }

  // N4: the isolation the profile lock relies on ------------------------------------------------

  @Test
  void theRuntimeRolesDefaultIsolationIsReadCommitted() throws SQLException {
    assertThat(jdbc.queryForObject("SHOW default_transaction_isolation", String.class))
        .isEqualTo("read committed");
    try (Connection c = openTransaction()) {
      assertThat(queryOne(c, "SHOW transaction_isolation", String.class))
          .isEqualTo("read committed");
    }
  }

  /** J4: the name written must be the approved application's own. */
  @Test
  void aNameOtherThanTheApprovedApplicationsIsRefused() throws SQLException {
    UUID id = newCustomer();
    UUID application = pendingApplication(id);

    assertRestrictViolation(
        NAME_ONLY_BY_APPROVAL,
        () ->
            inTransaction(
                c -> {
                  update(
                      c,
                      "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ?"
                          + " WHERE id = ?",
                      UUID.randomUUID(),
                      application);
                  update(
                      c,
                      "UPDATE usr.customers SET first_name = 'Somsri', last_name = 'Jaidee'"
                          + " WHERE user_id = ?",
                      id);
                }));
    assertThat(status(application)).as("rolled back with the name").isEqualTo("PENDING_REVIEW");
  }

  /** The approval's copy sets it once; nothing may change it afterwards (Q5). */
  @Test
  void theLegalNameIsSetOnceAndThenFixedEvenForTheOwner() throws SQLException {
    UUID id = newCustomer();
    approveAndCopyName(id, pendingApplication(id));

    assertRestrictViolation(
        "the legal name cannot change once set",
        () -> jdbc.update("UPDATE usr.customers SET first_name = 'Somsri' WHERE user_id = ?", id));
    assertRestrictViolation(
        "the legal name cannot change once set",
        () ->
            jdbc.update(
                "UPDATE usr.customers SET first_name = NULL, last_name = NULL WHERE user_id = ?",
                id));
    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                "the legal name cannot change once set",
                () ->
                    update(
                        owner,
                        "UPDATE usr.customers SET last_name = 'Other' WHERE user_id = ?",
                        id)));
  }

  /** Owner, 2026-11-03: date of birth and country are locked by the approval too. */
  @Test
  void theProfileIsLockedOnceTheLegalNameIsCopied() throws SQLException {
    UUID id = newCustomer();
    approveAndCopyName(id, pendingApplication(id));

    assertRestrictViolation(
        PROFILE_LOCKED,
        () -> jdbc.update("UPDATE usr.customers SET country_code = 'GB' WHERE user_id = ?", id));
    assertRestrictViolation(
        PROFILE_LOCKED,
        () ->
            jdbc.update(
                "UPDATE usr.customers SET date_of_birth = '1991-01-01' WHERE user_id = ?", id));
    // A statement that changes neither value is not refused.
    assertThat(
            jdbc.update(
                "UPDATE usr.customers SET country_code = country_code WHERE user_id = ?", id))
        .isEqualTo(1);
  }

  // Submit: profile required and copied (J5, K4) ----------------------------------------------

  private static final String SUBMIT_NEEDS_PROFILE =
      "cannot be submitted without a date of birth and a country";

  private static final String SUBMIT =
      "UPDATE usr.kyc_applications SET status = 'PENDING_REVIEW'" + " WHERE id = ?";

  /** J5: the database refuses a submit without the profile's date of birth or country. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"neither", "date_of_birth only", "country_code only"})
  void aSubmitWithoutADateOfBirthOrCountryIsRefusedEvenForTheOwner(String present)
      throws SQLException {
    UUID id = newCustomerWithoutProfile();
    if (present.startsWith("date_of_birth")) {
      jdbc.update("UPDATE usr.customers SET date_of_birth = '1990-05-17' WHERE user_id = ?", id);
    } else if (present.startsWith("country_code")) {
      jdbc.update("UPDATE usr.customers SET country_code = 'TH' WHERE user_id = ?", id);
    }
    UUID application = newKycApplication(id);

    assertRestrictViolation(SUBMIT_NEEDS_PROFILE, () -> jdbc.update(SUBMIT, application));
    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                SUBMIT_NEEDS_PROFILE, () -> update(owner, SUBMIT, application)));
    assertThat(status(application)).isEqualTo("DRAFT");
  }

  /** K4: the submit copies the profile's date of birth and country onto the application. */
  @Test
  void theSubmitCopiesTheProfileOntoTheApplication() throws SQLException {
    UUID id = newCustomerWithoutProfile();
    jdbc.update(
        "UPDATE usr.customers SET date_of_birth = '1984-02-29', country_code = 'GB'"
            + " WHERE user_id = ?",
        id);
    UUID application = newKycApplication(id);
    assertThat(snapshot(application)).containsEntry("date_of_birth", null);

    jdbc.update(SUBMIT, application);

    assertThat(snapshot(application))
        .containsEntry("date_of_birth", java.sql.Date.valueOf("1984-02-29"))
        .containsEntry("country_code", "GB");
  }

  /** K4: a later profile change, allowed after a rejection, leaves the snapshot as it was. */
  @Test
  void aProfileChangeAfterARejectionDoesNotAlterThePastApplicationsSnapshot() throws SQLException {
    UUID id = newCustomer();
    UUID rejected = newKycApplication(id);
    moveKycApplication(rejected, "REJECTED");

    jdbc.update(
        "UPDATE usr.customers SET date_of_birth = '1991-12-31', country_code = 'GB'"
            + " WHERE user_id = ?",
        id);
    UUID next = pendingApplication(id);

    assertThat(snapshot(rejected))
        .containsEntry("date_of_birth", java.sql.Date.valueOf("1990-05-17"))
        .containsEntry("country_code", "TH");
    assertThat(snapshot(next))
        .containsEntry("date_of_birth", java.sql.Date.valueOf("1991-12-31"))
        .containsEntry("country_code", "GB");
  }

  /** K4: nobody writes the snapshot directly: no grant, and the trigger keeps the stored values. */
  @Test
  void theSnapshotCannotBeWrittenDirectlyEvenByTheOwner() throws SQLException {
    UUID id = newCustomer();
    UUID application = pendingApplication(id);
    UUID draft = newKycApplication(newCustomer());

    assertPermissionDenied(
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET country_code = 'GB' WHERE id = ?", application));
    // A submitted application only changes by a decision; one that also tries to write the
    // snapshot keeps the stored values, as does a draft.
    asOwnerRolledBack(
        owner -> {
          update(
              owner,
              "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ?,"
                  + " date_of_birth = '2000-01-01', country_code = 'GB' WHERE id = ?",
              UUID.randomUUID(),
              application);
          update(
              owner,
              "UPDATE usr.kyc_applications SET date_of_birth = '2000-01-01', country_code = 'GB'"
                  + " WHERE id = ?",
              draft);
          assertThat(
                  queryOne(
                      owner,
                      "SELECT country_code FROM usr.kyc_applications WHERE id = ?",
                      String.class,
                      application))
              .isEqualTo("TH");
          assertThat(
                  queryOne(
                      owner,
                      "SELECT count(*) FROM usr.kyc_applications"
                          + " WHERE id = ? AND country_code IS NULL AND date_of_birth IS NULL",
                      Long.class,
                      draft))
              .isOne();
        });
    // With the triggers off, the check constraint still requires the snapshot on a submitted row.
    withTriggersDisabled(
        superuser ->
            assertCheckViolation(
                "ck_kyc_applications_profile_snapshot",
                () ->
                    update(
                        superuser,
                        "UPDATE usr.kyc_applications SET country_code = NULL WHERE id = ?",
                        application)));
  }

  private Map<String, Object> snapshot(UUID application) {
    return jdbc.queryForMap(
        "SELECT date_of_birth, country_code FROM usr.kyc_applications WHERE id = ?", application);
  }

  /** Owner follow-up #2 (2026-11-03): locked from submit onward, for every role. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"PENDING_REVIEW", "APPROVED"})
  void aSubmittedOrApprovedApplicationLocksTheProfileEvenForTheOwner(String status)
      throws SQLException {
    UUID id = newCustomer();
    UUID application = newKycApplication(id);
    moveKycApplication(application, status);

    assertRestrictViolation(
        PROFILE_LOCKED,
        () -> jdbc.update("UPDATE usr.customers SET country_code = 'GB' WHERE user_id = ?", id));
    assertRestrictViolation(
        PROFILE_LOCKED,
        () ->
            jdbc.update(
                "UPDATE usr.customers SET date_of_birth = '1990-01-01' WHERE user_id = ?", id));
    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                PROFILE_LOCKED,
                () ->
                    update(
                        owner,
                        "UPDATE usr.customers SET date_of_birth = '1990-01-01' WHERE user_id = ?",
                        id)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"DRAFT", "REJECTED"})
  void aDraftOrARejectedApplicationLeavesTheProfileEditable(String status) throws SQLException {
    UUID id = newCustomer();
    moveKycApplication(newKycApplication(id), status);

    assertThat(jdbc.update("UPDATE usr.customers SET country_code = 'TH' WHERE user_id = ?", id))
        .isEqualTo(1);
  }

  /** A rejection unlocks the profile for correction; the next submit locks it again. */
  @Test
  void theNextSubmitAfterARejectionLocksTheProfileAgain() throws SQLException {
    UUID id = newCustomer();
    moveKycApplication(newKycApplication(id), "REJECTED");
    assertThat(jdbc.update("UPDATE usr.customers SET country_code = 'GB' WHERE user_id = ?", id))
        .isEqualTo(1);

    UUID second = newKycApplication(id);
    assertThat(jdbc.update("UPDATE usr.customers SET country_code = 'TH' WHERE user_id = ?", id))
        .as("a new draft does not lock")
        .isEqualTo(1);
    moveKycApplication(second, "PENDING_REVIEW");

    assertRestrictViolation(
        PROFILE_LOCKED,
        () -> jdbc.update("UPDATE usr.customers SET country_code = 'GB' WHERE user_id = ?", id));
  }

  /**
   * The race (owner follow-up #2), at the database alone: a submit waits for a profile update in
   * flight and then submits the updated profile, so nothing changed under a submitted application.
   */
  @Test
  void aSubmitWaitsForAProfileUpdateInFlightAndTheProfileThenLocks() throws Exception {
    UUID id = newCustomer();
    UUID application = newKycApplication(id);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection profileUpdate = openTransaction()) {
      update(profileUpdate, "UPDATE usr.customers SET country_code = 'GB' WHERE user_id = ?", id);

      Future<Integer> submit =
          pool.submit(
              () -> {
                try (Connection c = dataSource.getConnection()) {
                  int pid = queryOne(c, "SELECT pg_backend_pid()", Integer.class);
                  backendPid.complete(pid);
                  return update(
                      c,
                      "UPDATE usr.kyc_applications SET status = 'PENDING_REVIEW' WHERE id = ?",
                      application);
                }
              });
      awaitLockWait(backendPid.get(20, TimeUnit.SECONDS));
      profileUpdate.commit();

      assertThat(submit.get(20, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(status(application)).isEqualTo("PENDING_REVIEW");
    assertThat(
            jdbc.queryForObject(
                "SELECT country_code FROM usr.customers WHERE user_id = ?", String.class, id))
        .isEqualTo("GB");
    // K4: the submit read the profile under its lock, so the snapshot holds the committed value.
    assertThat(snapshot(application)).containsEntry("country_code", "GB");
    assertRestrictViolation(
        PROFILE_LOCKED,
        () -> jdbc.update("UPDATE usr.customers SET country_code = 'TH' WHERE user_id = ?", id));
  }

  /**
   * The race the other way round: a profile update queued behind an uncommitted submit runs its
   * check only once it holds the row, sees the committed submit and is refused.
   */
  @Test
  void aProfileUpdateQueuedBehindASubmitIsRefused() throws Exception {
    UUID id = newCustomer();
    jdbc.update("UPDATE usr.customers SET country_code = 'TH' WHERE user_id = ?", id);
    UUID application = newKycApplication(id);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection submit = openTransaction()) {
      update(
          submit,
          "UPDATE usr.kyc_applications SET status = 'PENDING_REVIEW' WHERE id = ?",
          application);

      Future<Integer> profileUpdate =
          pool.submit(
              () -> {
                try (Connection c = dataSource.getConnection()) {
                  backendPid.complete(queryOne(c, "SELECT pg_backend_pid()", Integer.class));
                  return update(
                      c, "UPDATE usr.customers SET country_code = 'GB' WHERE user_id = ?", id);
                }
              });
      awaitLockWait(backendPid.get(20, TimeUnit.SECONDS));
      submit.commit();

      assertThatThrownBy(() -> profileUpdate.get(20, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasMessageContaining("locked by a submitted or approved KYC application");
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT country_code FROM usr.customers WHERE user_id = ?", String.class, id))
        .isEqualTo("TH");
  }

  private final CompletableFuture<Integer> backendPid = new CompletableFuture<>();

  // KYC applications: insert -----------------------------------------------------------------

  @Test
  void anApplicationNeedsItsCustomer() {
    assertForeignKeyViolation(
        "fk_kyc_applications_customer",
        () -> insert("usr.kyc_applications", validKycApplication(UUID.randomUUID())));
  }

  static Stream<Arguments> invalidApplicationColumns() {
    return Stream.of(
        Arguments.of("verification_level", "PREMIUM", "ck_kyc_applications_verification_level"),
        Arguments.of("document_type", "DRIVING_LICENCE", "ck_kyc_applications_document_type"),
        Arguments.of("document_reference", " ", "ck_kyc_applications_content_not_blank"),
        Arguments.of("first_name", "", "ck_kyc_applications_content_not_blank"),
        Arguments.of("last_name", "  ", "ck_kyc_applications_content_not_blank"));
  }

  @ParameterizedTest(name = "{0} = ''{1}''")
  @MethodSource("invalidApplicationColumns")
  void applicationChecksRejectInvalidValues(String column, String value, String constraint)
      throws SQLException {
    UUID customer = newCustomer();

    assertCheckViolation(
        constraint,
        () -> insert("usr.kyc_applications", with(validKycApplication(customer), column, value)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"verification_level", "document_type", "document_reference", "first_name"})
  void applicationContentIsRequired(String column) throws SQLException {
    Map<String, Object> application = validKycApplication(newCustomer());
    application.remove(column);

    assertSqlState(NOT_NULL_VIOLATION, () -> insert("usr.kyc_applications", application));
  }

  @Test
  void contentLongerThanTheColumnsDoesNotFit() throws SQLException {
    UUID customer = newCustomer();

    assertSqlState(
        "22001",
        () ->
            insert(
                "usr.kyc_applications",
                with(validKycApplication(customer), "document_reference", "x".repeat(51))));
    assertSqlState(
        "22001",
        () ->
            insert(
                "usr.kyc_applications",
                with(validKycApplication(customer), "first_name", "x".repeat(101))));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"PENDING_REVIEW", "APPROVED", "REJECTED"})
  void anApplicationCanOnlyBeCreatedAsADraft(String status) throws SQLException {
    UUID customer = newCustomer();

    assertRestrictViolation(
        "an application is created as DRAFT",
        () ->
            insert("usr.kyc_applications", with(validKycApplication(customer), "status", status)));
  }

  @Test
  void insertSetsTheTimesAndClearsDecisionTimes() throws SQLException {
    UUID customer = newCustomer();
    Map<String, Object> application =
        with(
            with(
                with(validKycApplication(customer), "created_at", BACKDATED),
                "submitted_at",
                BACKDATED),
            "decided_at",
            BACKDATED);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, "usr.kyc_applications", application);
        });

    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT created_at = ? AS created, updated_at = ? AS updated,"
                + " submitted_at IS NULL AS unsubmitted, decided_at IS NULL AS undecided,"
                + " status = 'DRAFT' AS draft FROM usr.kyc_applications WHERE id = ?",
            now[0],
            now[0],
            application.get("id"));
    assertThat(stored.values()).containsOnly(true);
  }

  // One active application -------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"DRAFT", "PENDING_REVIEW", "APPROVED"})
  void anActiveApplicationBlocksASecond(String status) throws SQLException {
    UUID customer = newCustomer();
    moveKycApplication(newKycApplication(customer), status);

    assertUniqueViolation(
        "uq_kyc_applications_active",
        () -> insert("usr.kyc_applications", validKycApplication(customer)));
    // The conflict-safe insert the application uses returns nothing instead.
    assertThat(
            jdbc.update(
                "INSERT INTO usr.kyc_applications (customer_id, verification_level,"
                    + " document_type, document_reference, first_name, last_name)"
                    + " VALUES (?, 'BASIC', 'ID_CARD', 'X1', 'A', 'B')"
                    + " ON CONFLICT (customer_id)"
                    + " WHERE status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED') DO NOTHING",
                customer))
        .isZero();
  }

  @Test
  void aRejectedCustomerMayApplyAgainAndOtherCustomersAreIndependent() throws SQLException {
    UUID customer = newCustomer();
    moveKycApplication(newKycApplication(customer), "REJECTED");
    moveKycApplication(newKycApplication(customer), "REJECTED");

    newKycApplication(customer);
    newKycApplication(newCustomer());
  }

  // Transitions ------------------------------------------------------------------------------

  @Test
  void aDraftCanBeEditedAndSubmittedAndTheDatabaseSetsTheSubmissionTime() throws SQLException {
    UUID application = newKycApplication(newCustomer());
    OffsetDateTime[] statement = new OffsetDateTime[1];

    jdbc.update(
        "UPDATE usr.kyc_applications SET document_reference = 'ZZ999', first_name = 'Somsri',"
            + " verification_level = 'FULL', document_type = 'PASSPORT' WHERE id = ?",
        application);
    inTransaction(
        c -> {
          update(
              c,
              "UPDATE usr.kyc_applications SET status = 'PENDING_REVIEW' WHERE id = ?",
              application);
          statement[0] = transactionNow(c);
        });

    assertThat(status(application)).isEqualTo("PENDING_REVIEW");
    OffsetDateTime submitted =
        readTime("SELECT submitted_at FROM usr.kyc_applications WHERE id = ?", application);
    assertThat(submitted).isAfterOrEqualTo(statement[0]);
    assertThat(readTime("SELECT decided_at FROM usr.kyc_applications WHERE id = ?", application))
        .isNull();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"APPROVED", "REJECTED"})
  void aDecisionSetsItsTimeAndKeepsTheSubmissionTime(String decision) throws SQLException {
    UUID application = newKycApplication(newCustomer());
    moveKycApplication(application, "PENDING_REVIEW");
    OffsetDateTime submitted =
        readTime("SELECT submitted_at FROM usr.kyc_applications WHERE id = ?", application);

    moveKycApplication(application, decision);

    assertThat(readTime("SELECT submitted_at FROM usr.kyc_applications WHERE id = ?", application))
        .isEqualTo(submitted);
    assertThat(readTime("SELECT decided_at FROM usr.kyc_applications WHERE id = ?", application))
        .isAfterOrEqualTo(submitted);
  }

  static Stream<Arguments> refusedTransitions() {
    return Stream.of(
        Arguments.of("DRAFT", "APPROVED"),
        Arguments.of("DRAFT", "REJECTED"),
        Arguments.of("PENDING_REVIEW", "DRAFT"),
        Arguments.of("PENDING_REVIEW", "PENDING_REVIEW"));
  }

  @ParameterizedTest(name = "{0} to {1}")
  @MethodSource("refusedTransitions")
  void otherTransitionsAreRefused(String from, String to) throws SQLException {
    UUID application = newKycApplication(newCustomer());
    moveKycApplication(application, from);

    assertRestrictViolation(
        "cannot move from " + from + " to " + to,
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET status = ?, reviewer_id = ?,"
                    + " rejection_reason = CASE WHEN ? = 'REJECTED' THEN 'r' END WHERE id = ?",
                to,
                UUID.randomUUID(),
                to,
                application));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"APPROVED", "REJECTED"})
  void decidedApplicationsAreFinalEvenForTheOwner(String decision) throws SQLException {
    UUID application = newKycApplication(newCustomer());
    moveKycApplication(application, decision);

    assertRestrictViolation(
        "can no longer change",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET first_name = first_name WHERE id = ?",
                application));
    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                "can no longer change",
                () ->
                    update(
                        owner,
                        "UPDATE usr.kyc_applications SET status = 'DRAFT', reviewer_id = NULL,"
                            + " rejection_reason = NULL, submitted_at = NULL, decided_at = NULL"
                            + " WHERE id = ?",
                        application)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "verification_level = 'FULL'",
        "document_type = 'PASSPORT'",
        "document_reference = 'CHANGED1'",
        "first_name = 'Changed'",
        "last_name = 'Changed'"
      })
  void contentIsFixedOnceSubmitted(String change) throws SQLException {
    UUID application = newKycApplication(newCustomer());
    moveKycApplication(application, "PENDING_REVIEW");

    assertRestrictViolation(
        "can only be edited as a DRAFT",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET "
                    + change
                    + ", status = 'APPROVED',"
                    + " reviewer_id = ? WHERE id = ?",
                UUID.randomUUID(),
                application));
  }

  @Test
  void theOwnerCannotChangeAnApplicationsIdentity() throws SQLException {
    UUID application = newKycApplication(newCustomer());
    UUID other = newCustomer();

    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                "cannot change its identity",
                () ->
                    update(
                        owner,
                        "UPDATE usr.kyc_applications SET customer_id = ? WHERE id = ?",
                        other,
                        application)));
  }

  // Decision fields --------------------------------------------------------------------------

  @Test
  void aDecisionNeedsAReviewerAndARejectionAReason() throws SQLException {
    UUID application = newKycApplication(newCustomer());
    moveKycApplication(application, "PENDING_REVIEW");

    assertCheckViolation(
        "ck_kyc_applications_decided",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET status = 'APPROVED' WHERE id = ?", application));
    assertCheckViolation(
        "ck_kyc_applications_rejection_reason",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET status = 'REJECTED', reviewer_id = ?"
                    + " WHERE id = ?",
                UUID.randomUUID(),
                application));
    assertCheckViolation(
        "ck_kyc_applications_rejection_reason",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ?,"
                    + " rejection_reason = 'why' WHERE id = ?",
                UUID.randomUUID(),
                application));
    assertCheckViolation(
        "ck_kyc_applications_rejection_reason_not_blank",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET status = 'REJECTED', reviewer_id = ?,"
                    + " rejection_reason = ' ' WHERE id = ?",
                UUID.randomUUID(),
                application));
    assertThat(status(application)).isEqualTo("PENDING_REVIEW");
  }

  @Test
  void anUndecidedApplicationHasNoReviewer() throws SQLException {
    UUID application = newKycApplication(newCustomer());

    assertCheckViolation(
        "ck_kyc_applications_decided",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET reviewer_id = ? WHERE id = ?",
                UUID.randomUUID(),
                application));
  }

  @Test
  void theApplicantCannotBeTheReviewer() throws SQLException {
    UUID customer = newCustomer();
    UUID application = newKycApplication(customer);
    moveKycApplication(application, "PENDING_REVIEW");

    assertCheckViolation(
        "ck_kyc_applications_reviewer_not_customer",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ?"
                    + " WHERE id = ?",
                customer,
                application));
  }

  @Test
  void aRejectionReasonLongerThan500CharactersDoesNotFit() throws SQLException {
    UUID application = newKycApplication(newCustomer());
    moveKycApplication(application, "PENDING_REVIEW");

    assertSqlState(
        "22001",
        () ->
            jdbc.update(
                "UPDATE usr.kyc_applications SET status = 'REJECTED', reviewer_id = ?,"
                    + " rejection_reason = ? WHERE id = ?",
                UUID.randomUUID(),
                "x".repeat(501),
                application));
  }
}
