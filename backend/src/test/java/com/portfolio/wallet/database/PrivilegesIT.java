package com.portfolio.wallet.database;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.support.PostgresTestDatabase;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Grants of the runtime role {@code wallet_app} (documentations/06 MVP-ADR-02 "Database
 * protections"; doc 08 §4.6 risk "grants miss tables added by later migrations").
 *
 * <p>The expectation lists every table in the module schemas, so a new table without a decided
 * privilege set fails {@link #runtimeRolePrivilegesMatchTheIntendedSetForEveryTable()}.
 */
class PrivilegesIT extends DatabaseIntegrationTest {

  private static final String[] MODULE_SCHEMAS = {
    "auth", "usr", "wallet", "transfer", "ledger", "risk", "audit", "notification"
  };

  private static final String[] TABLE_PRIVILEGES = {
    "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN"
  };

  /** Append-only: read and insert only. */
  private static final Set<String> APPEND_ONLY = Set.of("SELECT", "INSERT");

  /** Updated in place, never deleted. */
  private static final Set<String> NO_DELETE = Set.of("SELECT", "INSERT", "UPDATE");

  /** Purged by a scheduled job (doc 07 D04). */
  private static final Set<String> PURGEABLE = Set.of("SELECT", "INSERT", "UPDATE", "DELETE");

  /** Recorded and purged by a scheduled job, never changed (MVP-ADR-20). */
  private static final Set<String> APPEND_AND_PURGE = Set.of("SELECT", "INSERT", "DELETE");

  private static final Map<String, Set<String>> EXPECTED_TABLE_PRIVILEGES =
      Map.ofEntries(
          entry("ledger.financial_transactions", APPEND_ONLY),
          entry("ledger.ledger_entries", APPEND_ONLY),
          // UPDATE only on the status column; see ledgerAccountsAllowUpdateOfStatusOnly().
          entry("ledger.ledger_accounts", APPEND_ONLY),
          entry("wallet.deposits", APPEND_ONLY),
          entry("wallet.withdrawals", APPEND_ONLY),
          entry("risk.risk_assessments", APPEND_ONLY),
          entry("risk.risk_rule_hits", APPEND_ONLY),
          entry("risk.risk_reviews", APPEND_ONLY),
          entry("audit.audit_logs", APPEND_ONLY),
          entry("wallet.wallets", NO_DELETE),
          entry("wallet.daily_limit_usage", NO_DELETE),
          entry("transfer.transfers", NO_DELETE),
          entry("risk.investigations", NO_DELETE),
          entry("wallet.idempotency_records", PURGEABLE),
          entry("transfer.idempotency_records", PURGEABLE),
          entry("wallet.lookup_attempts", APPEND_AND_PURGE),
          // Per-sender lock rows: never deleted (owner, 2026-10-30).
          entry("risk.sender_risk_state", NO_DELETE));

  @Test
  void runtimeRolePrivilegesMatchTheIntendedSetForEveryTable() {
    Map<String, Set<String>> actual = new TreeMap<>();
    jdbc.query(
        "SELECT n.nspname || '.' || c.relname AS name,"
            + " ARRAY(SELECT p FROM unnest(?::text[]) AS p"
            + "       WHERE has_table_privilege(?::name, c.oid, p)) AS privileges"
            + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = ANY (?::text[]) AND c.relkind IN ('r', 'p', 'v', 'm', 'f')",
        (ResultSet rs) -> {
          actual.put(rs.getString("name"), Set.copyOf(strings(rs.getArray("privileges"))));
        },
        TABLE_PRIVILEGES,
        PostgresTestDatabase.APP_ROLE,
        MODULE_SCHEMAS);

    assertThat(actual).isEqualTo(new TreeMap<>(EXPECTED_TABLE_PRIVILEGES));
  }

  @Test
  void ledgerAccountsAllowUpdateOfStatusOnly() {
    List<String> updatableColumns =
        jdbc.queryForList(
            "SELECT attname FROM pg_attribute"
                + " WHERE attrelid = 'ledger.ledger_accounts'::regclass AND attnum > 0"
                + " AND NOT attisdropped"
                + " AND has_column_privilege(?::name, attrelid, attnum, 'UPDATE')",
            String.class,
            PostgresTestDatabase.APP_ROLE);

    assertThat(updatableColumns).containsExactly("status");
  }

  @Test
  void runtimeRoleMayUseButNotAlterSequences() {
    Map<String, Set<String>> actual = new TreeMap<>();
    jdbc.query(
        "SELECT n.nspname || '.' || c.relname AS name,"
            + " ARRAY(SELECT p FROM unnest(ARRAY['USAGE', 'SELECT', 'UPDATE']) AS p"
            + "       WHERE has_sequence_privilege(?::name, c.oid, p)) AS privileges"
            + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = ANY (?::text[]) AND c.relkind = 'S'",
        (ResultSet rs) -> {
          actual.put(rs.getString("name"), Set.copyOf(strings(rs.getArray("privileges"))));
        },
        PostgresTestDatabase.APP_ROLE,
        MODULE_SCHEMAS);

    // Identity sequences of audit.audit_logs and wallet.lookup_attempts.
    assertThat(actual).isNotEmpty();
    assertThat(actual.values()).containsOnly(Set.of("USAGE", "SELECT"));
  }

  @Test
  void runtimeRoleCanUseButNotCreateInModuleSchemas() {
    for (String schema : MODULE_SCHEMAS) {
      assertThat(schemaPrivilege(schema, "USAGE")).as("USAGE on %s", schema).isTrue();
      assertThat(schemaPrivilege(schema, "CREATE")).as("CREATE on %s", schema).isFalse();
    }
  }

  @Test
  void runtimeRoleCannotCreateObjectsAnywhere() {
    assertThat(schemaPrivilege("public", "CREATE")).isFalse();
    assertThat(schemaPrivilege("flyway", "CREATE")).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_database_privilege(?::name, current_database(), 'CREATE')",
                Boolean.class,
                PostgresTestDatabase.APP_ROLE))
        .isFalse();
  }

  /**
   * V1 revokes the PUBLIC EXECUTE default on functions (owner, 2026-10-30). The trigger functions
   * still run for wallet_app, as every constraint IT shows: trigger execution checks no EXECUTE.
   */
  @Test
  void runtimeRoleCannotExecuteAnyFunctionInTheModuleSchemas() {
    List<String> executable =
        jdbc.queryForList(
            "SELECT n.nspname || '.' || p.proname FROM pg_proc p"
                + " JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = ANY (?::text[])"
                + " AND has_function_privilege(?::name, p.oid, 'EXECUTE')",
            String.class,
            MODULE_SCHEMAS,
            PostgresTestDatabase.APP_ROLE);
    Integer functions =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = ANY (?::text[])",
            Integer.class,
            (Object) MODULE_SCHEMAS);

    assertThat(functions).isPositive();
    assertThat(executable).isEmpty();
  }

  @Test
  void runtimeRoleCannotReachTheFlywayHistory() {
    assertThat(schemaPrivilege("flyway", "USAGE")).isFalse();
  }

  private Boolean schemaPrivilege(String schema, String privilege) {
    return jdbc.queryForObject(
        "SELECT has_schema_privilege(?::name, ?::text, ?::text)",
        Boolean.class,
        PostgresTestDatabase.APP_ROLE,
        schema,
        privilege);
  }

  private static List<String> strings(Array array) throws SQLException {
    return List.of((String[]) array.getArray());
  }
}
