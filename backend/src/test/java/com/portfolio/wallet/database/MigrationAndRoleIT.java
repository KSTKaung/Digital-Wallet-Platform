package com.portfolio.wallet.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.support.PostgresTestDatabase;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Acceptance criteria 1 and 2 (documentations/08 §4.4; test cases 1 and 2): Flyway migrates an
 * empty database as {@code wallet_owner}, a second run changes nothing, the context starts with
 * {@code ddl-auto: validate}, and the application runs as the unprivileged {@code wallet_app}.
 */
class MigrationAndRoleIT extends DatabaseIntegrationTest {

  private static final List<String> MODULE_SCHEMAS =
      List.of("auth", "usr", "wallet", "transfer", "ledger", "risk", "audit", "notification");

  @Autowired Environment environment;
  @Autowired EntityManagerFactory entityManagerFactory;

  // Criterion 1 -------------------------------------------------------------------------------

  @Test
  void flywayCreatedItsOwnSchemaSoTheDatabaseStartedEmpty() {
    // Flyway writes this marker row only when it had to create the "flyway" schema itself.
    assertThat(flyway.info().applied())
        .anyMatch(m -> m.getType() == CoreMigrationType.SCHEMA && m.getState().isApplied());
  }

  @Test
  void everyMigrationIsAppliedSuccessfullyByTheOwnerRole() {
    MigrationInfo[] applied = flyway.info().applied();

    assertThat(applied).isNotEmpty();
    assertThat(applied).allMatch(m -> m.getState().isApplied() && !m.getState().isFailed());
    assertThat(Arrays.stream(applied).map(MigrationInfo::getInstalledBy))
        .containsOnly(PostgresTestDatabase.OWNER_ROLE);
  }

  @Test
  void noMigrationIsPendingAndValidationSucceeds() {
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  void runningMigrateAgainChangesNothing() {
    int appliedBefore = flyway.info().applied().length;

    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(flyway.info().applied()).hasSize(appliedBefore);
  }

  @Test
  void contextStartsWithHibernateSchemaValidation() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(entityManagerFactory.isOpen()).isTrue();
  }

  @Test
  void everyModuleSchemaAndTheFlywaySchemaExist() {
    List<String> schemas =
        jdbc.queryForList(
            "SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\\_%'"
                + " AND nspname NOT IN ('information_schema', 'public')",
            String.class);

    assertThat(schemas).containsExactlyInAnyOrderElementsOf(withFlyway(MODULE_SCHEMAS));
  }

  @Test
  void publicSchemaHoldsNoTables() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'public'",
                Integer.class))
        .isZero();
  }

  // Criterion 2 -------------------------------------------------------------------------------

  @Test
  void applicationConnectsAsWalletAppWithoutSwitchingRole() {
    Map<String, Object> users = jdbc.queryForMap("SELECT current_user, session_user");

    assertThat(users.get("current_user")).isEqualTo(PostgresTestDatabase.APP_ROLE);
    assertThat(users.get("session_user")).isEqualTo(PostgresTestDatabase.APP_ROLE);
  }

  @Test
  void runtimeRoleHasNoElevatedAttributes() {
    Map<String, Object> role =
        jdbc.queryForMap(
            "SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls"
                + " FROM pg_roles WHERE rolname = current_user");

    assertThat(role)
        .containsOnlyKeys(
            "rolsuper", "rolcreaterole", "rolcreatedb", "rolreplication", "rolbypassrls");
    assertThat(role.values()).containsOnly(false);
  }

  @Test
  void ownerRoleIsNotASuperuser() {
    Map<String, Object> role =
        jdbc.queryForMap(
            "SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls"
                + " FROM pg_roles WHERE rolname = ?::name",
            PostgresTestDatabase.OWNER_ROLE);

    assertThat(role.values()).containsOnly(false);
  }

  @Test
  void runtimeRoleIsMemberOfNoOtherRole() {
    // Membership in wallet_owner would hand ownership (ALTER, DISABLE TRIGGER) to the app.
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_has_role(current_user, ?::name, 'MEMBER')",
                Boolean.class,
                PostgresTestDatabase.OWNER_ROLE))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member"
                    + " WHERE r.rolname = current_user",
                Integer.class))
        .isZero();
  }

  @Test
  void runtimeRoleOwnsNoSchemaAndNoRelation() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_namespace WHERE nspowner = "
                    + "(SELECT oid FROM pg_roles WHERE rolname = current_user)",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relowner = "
                    + "(SELECT oid FROM pg_roles WHERE rolname = current_user)",
                Integer.class))
        .isZero();
  }

  @Test
  void ownerRoleOwnsEveryApplicationSchemaAndRelation() {
    List<String> schemas = withFlyway(MODULE_SCHEMAS);

    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT pg_get_userbyid(nspowner) FROM pg_namespace"
                    + " WHERE nspname = ANY (?::text[])",
                String.class,
                (Object) schemas.toArray(String[]::new)))
        .containsExactly(PostgresTestDatabase.OWNER_ROLE);
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT pg_get_userbyid(c.relowner) FROM pg_class c"
                    + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = ANY (?::text[])",
                String.class,
                (Object) schemas.toArray(String[]::new)))
        .containsExactly(PostgresTestDatabase.OWNER_ROLE);
  }

  @Test
  void ownerDataSourceUsedByTheseTestsIsTheOwnerRole() throws Exception {
    try (Connection owner = ownerDataSource().getConnection()) {
      assertThat(queryOne(owner, "SELECT current_user", String.class))
          .isEqualTo(PostgresTestDatabase.OWNER_ROLE);
    }
  }

  private static List<String> withFlyway(List<String> schemas) {
    return Stream.concat(schemas.stream(), Stream.of("flyway")).toList();
  }
}
