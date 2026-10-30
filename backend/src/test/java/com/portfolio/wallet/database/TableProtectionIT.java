package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.assertPermissionDenied;
import static com.portfolio.wallet.support.SqlAssertions.assertRestrictViolation;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Append-only and never-deleted tables (documentations/06 MVP-ADR-02 "Database protections";
 * acceptance criterion 3, test case 3).
 *
 * <p>For {@code wallet_app} the grants refuse first (SQLSTATE 42501), so the triggers are proven
 * separately: as the table owner {@code wallet_owner}, which holds every privilege, UPDATE, DELETE
 * and TRUNCATE still fail with the trigger's restrict_violation (23001). Owner statements always
 * run in a transaction that is rolled back.
 */
class TableProtectionIT extends DatabaseIntegrationTest {

  /** {@code column} is set to itself in the UPDATE probe; {@code key} selects the fixture row. */
  record ProtectedTable(String name, String key, String column) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<ProtectedTable> appendOnlyTables() {
    return Stream.of(
        new ProtectedTable("ledger.ledger_entries", "transaction_id", "amount"),
        new ProtectedTable("ledger.financial_transactions", "id", "correlation_id"),
        new ProtectedTable("wallet.deposits", "id", "amount"),
        new ProtectedTable("wallet.withdrawals", "id", "amount"),
        new ProtectedTable("risk.risk_assessments", "id", "score"),
        new ProtectedTable("risk.risk_rule_hits", "assessment_id", "weight"),
        new ProtectedTable("risk.risk_reviews", "id", "reason"),
        new ProtectedTable("audit.audit_logs", "correlation_id", "result"));
  }

  static Stream<ProtectedTable> noDeleteTables() {
    return Stream.of(
        new ProtectedTable("wallet.wallets", "id", "tier"),
        new ProtectedTable("wallet.daily_limit_usage", "wallet_id", "used"),
        new ProtectedTable("transfer.transfers", "id", "status"),
        new ProtectedTable("ledger.ledger_accounts", "id", "status"),
        new ProtectedTable("risk.investigations", "id", "outcome"),
        // Per-sender lock rows: never deleted (owner, 2026-10-30; PrivilegesIT NO_DELETE).
        new ProtectedTable("risk.sender_risk_state", "customer_id", "updated_at"));
  }

  // Append-only tables, as wallet_app ---------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void runtimeRoleCannotUpdateAppendOnlyTable(ProtectedTable table) throws SQLException {
    Object key = existingRow(table);

    assertPermissionDenied(() -> jdbc.update(updateProbe(table), key));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void runtimeRoleCannotDeleteFromAppendOnlyTable(ProtectedTable table) throws SQLException {
    Object key = existingRow(table);

    assertPermissionDenied(() -> jdbc.update(deleteProbe(table), key));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void runtimeRoleCannotTruncateAppendOnlyTable(ProtectedTable table) throws SQLException {
    existingRow(table);

    assertPermissionDenied(() -> jdbc.execute("TRUNCATE " + table.name()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void runtimeRoleCannotDisableAppendOnlyTriggers(ProtectedTable table) {
    assertPermissionDenied(
        () -> jdbc.execute("ALTER TABLE " + table.name() + " DISABLE TRIGGER ALL"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void runtimeRoleCannotDropAppendOnlyTriggers(ProtectedTable table) {
    String trigger =
        jdbc.queryForObject(
            "SELECT tgname FROM pg_trigger WHERE tgrelid = ?::regclass AND NOT tgisinternal"
                + " AND tgname LIKE '%append_only'",
            String.class, table.name());

    assertPermissionDenied(() -> jdbc.execute("DROP TRIGGER " + trigger + " ON " + table.name()));
  }

  // Append-only tables, triggers proven as the owner ------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void triggerRejectsUpdateEvenForTheOwner(ProtectedTable table) throws SQLException {
    Object key = existingRow(table);

    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                "UPDATE on " + table.name() + " is not allowed",
                () -> update(owner, updateProbe(table), key)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void triggerRejectsDeleteEvenForTheOwner(ProtectedTable table) throws SQLException {
    Object key = existingRow(table);

    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                "DELETE on " + table.name() + " is not allowed",
                () -> update(owner, deleteProbe(table), key)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void triggerRejectsTruncateEvenForTheOwner(ProtectedTable table) throws SQLException {
    existingRow(table);

    // CASCADE: tables referenced by a foreign key cannot be truncated alone, and the error
    // would otherwise come from the FK check instead of the trigger.
    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                "TRUNCATE on",
                () -> {
                  try (Statement statement = owner.createStatement()) {
                    statement.execute("TRUNCATE " + table.name() + " CASCADE");
                  }
                }));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("appendOnlyTables")
  void appendOnlyTriggersAreInstalledAndEnabled(ProtectedTable table) {
    List<Map<String, Object>> triggers =
        jdbc.queryForList(
            "SELECT pg_get_triggerdef(t.oid) AS definition, t.tgenabled::text AS enabled"
                + " FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid"
                + " WHERE t.tgrelid = ?::regclass AND NOT t.tgisinternal"
                + " AND p.proname = 'reject_modification'",
            table.name());

    assertThat(triggers).extracting(t -> t.get("enabled")).containsOnly("O");
    assertThat(triggers)
        .extracting(t -> (String) t.get("definition"))
        .anyMatch(d -> d.contains("BEFORE DELETE OR UPDATE") && d.contains("FOR EACH ROW"))
        .anyMatch(d -> d.contains("BEFORE TRUNCATE") && d.contains("FOR EACH STATEMENT"));
  }

  // Never-deleted tables ----------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("noDeleteTables")
  void runtimeRoleCannotDeleteFromNoDeleteTable(ProtectedTable table) throws SQLException {
    Object key = existingRow(table);

    assertPermissionDenied(() -> jdbc.update(deleteProbe(table), key));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + table.name() + " WHERE " + table.key() + " = ?",
                Integer.class,
                key))
        .isEqualTo(1);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("noDeleteTables")
  void runtimeRoleCannotTruncateNoDeleteTable(ProtectedTable table) {
    assertPermissionDenied(() -> jdbc.execute("TRUNCATE " + table.name()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("noDeleteTables")
  void runtimeRoleCanUpdateNoDeleteTable(ProtectedTable table) throws SQLException {
    Object key = existingRow(table);

    assertThat(jdbc.update(updateProbe(table), key)).isEqualTo(1);
  }

  // Ledger accounts: only the status may change -----------------------------------------------

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"owner_reference", "code", "account_type", "currency", "created_at"})
  void ledgerAccountIdentityCannotChange(String column) throws SQLException {
    UUID account = newCustomerLedgerAccount();

    assertPermissionDenied(
        () ->
            jdbc.update(
                "UPDATE ledger.ledger_accounts SET " + column + " = " + column + " WHERE id = ?",
                account));
  }

  @Test
  void ledgerAccountStatusCanChange() throws SQLException {
    UUID account = newCustomerLedgerAccount();

    assertThat(
            jdbc.update(
                "UPDATE ledger.ledger_accounts SET status = 'FROZEN' WHERE id = ?", account))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM ledger.ledger_accounts WHERE id = ?", String.class, account))
        .isEqualTo("FROZEN");
  }

  @ParameterizedTest(name = "CLOSED to {0}")
  @ValueSource(strings = {"ACTIVE", "FROZEN"})
  void closedLedgerAccountCannotChangeItsStatus(String status) throws SQLException {
    UUID account = newCustomerLedgerAccount();
    jdbc.update("UPDATE ledger.ledger_accounts SET status = 'CLOSED' WHERE id = ?", account);

    assertRestrictViolation(
        "is CLOSED; its status can no longer change",
        () ->
            jdbc.update(
                "UPDATE ledger.ledger_accounts SET status = ? WHERE id = ?", status, account));
  }

  // Lookup attempts: recorded and purged, never changed ---------------------------------------

  @Test
  void runtimeRoleCannotUpdateOrTruncateLookupAttemptsButCanPurgeThem() {
    UUID user = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO wallet.lookup_attempts (user_id, client_ip) VALUES (?, '192.0.2.1'::inet)",
        user);

    assertPermissionDenied(
        () ->
            jdbc.update(
                "UPDATE wallet.lookup_attempts SET attempted_at = attempted_at WHERE user_id = ?",
                user));
    assertPermissionDenied(() -> jdbc.execute("TRUNCATE wallet.lookup_attempts"));
    assertThat(jdbc.update("DELETE FROM wallet.lookup_attempts WHERE user_id = ?", user))
        .isEqualTo(1);
  }

  // Helpers -----------------------------------------------------------------------------------

  private static String updateProbe(ProtectedTable table) {
    return "UPDATE "
        + table.name()
        + " SET "
        + table.column()
        + " = "
        + table.column()
        + " WHERE "
        + table.key()
        + " = ?";
  }

  private static String deleteProbe(ProtectedTable table) {
    return "DELETE FROM " + table.name() + " WHERE " + table.key() + " = ?";
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

  /** Creates (and commits) one row in {@code table}; returns the value of its key column. */
  private Object existingRow(ProtectedTable table) throws SQLException {
    return switch (table.name()) {
      case "ledger.ledger_entries", "ledger.financial_transactions" ->
          postDeposit(newCustomerLedgerAccount(), "10.00");
      case "wallet.deposits", "wallet.withdrawals" -> {
        Map<String, Object> movement = validMoneyMovement(newWallet());
        insert(table.name(), movement);
        yield movement.get("id");
      }
      case "risk.risk_assessments" -> newRiskAssessment();
      case "risk.risk_rule_hits" -> {
        UUID assessment = newRiskAssessment();
        insert(
            "risk.risk_rule_hits",
            row("assessment_id", assessment, "rule_code", "LARGE_AMOUNT", "weight", 30));
        yield assessment;
      }
      case "risk.risk_reviews" -> {
        Map<String, Object> review = validRiskReview(newRiskAssessment(), "EXPIRE", null);
        insert("risk.risk_reviews", review);
        yield review.get("id");
      }
      case "audit.audit_logs" -> {
        String correlationId = UUID.randomUUID().toString();
        insert(
            "audit.audit_logs",
            row(
                "actor_type",
                "SYSTEM",
                "action",
                "TEST",
                "resource",
                "TEST",
                "result",
                "SUCCESS",
                "correlation_id",
                correlationId));
        yield correlationId;
      }
      case "wallet.wallets" -> newWallet();
      case "wallet.daily_limit_usage" -> {
        UUID wallet = newWallet();
        insert(
            "wallet.daily_limit_usage",
            row("wallet_id", wallet, "operation", "TRANSFER", "limit_date", LocalDate.now()));
        yield wallet;
      }
      // PENDING_REVIEW: the update guard lets a held transfer keep its status.
      case "transfer.transfers" -> newTransfer("PENDING_REVIEW");
      case "ledger.ledger_accounts" -> newCustomerLedgerAccount();
      case "risk.sender_risk_state" -> {
        UUID customer = UUID.randomUUID();
        insert("risk.sender_risk_state", row("customer_id", customer));
        yield customer;
      }
      case "risk.investigations" -> {
        UUID id = UUID.randomUUID();
        insert(
            "risk.investigations",
            row("id", id, "assessment_id", newRiskAssessment(), "opened_at", OffsetDateTime.now()));
        yield id;
      }
      default -> throw new IllegalArgumentException("no fixture for " + table.name());
    };
  }
}
