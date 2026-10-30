package com.portfolio.wallet.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for the database integration tests (PR 2, documentations/08 §4.5 cases 1–8 and 12).
 *
 * <p>All subclasses share one Spring context (the {@link DynamicPropertySource} method is declared
 * once, here) and therefore one connection pool. {@link #dataSource} and {@link #jdbc} connect as
 * the runtime role {@code wallet_app}: every behaviour check runs as that role unless a test says
 * otherwise. {@link #ownerDataSource()} is Flyway's connection as {@code wallet_owner} (not a
 * superuser); it is used only to prove that the append-only triggers fire independently of the
 * grants, always inside a transaction that is rolled back.
 *
 * <p>Rows are never cleaned up: append-only tables cannot be, so every fixture uses fresh random
 * ids and tests never depend on table row counts.
 */
@SpringBootTest
public abstract class DatabaseIntegrationTest {

  protected static final String THB = "THB";

  /** An application-supplied timestamp that the database must overwrite or reject. */
  protected static final OffsetDateTime BACKDATED =
      OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.register(registry);
  }

  @Autowired protected DataSource dataSource;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected Flyway flyway;

  /** Flyway's DataSource: connects as {@code wallet_owner}. */
  protected DataSource ownerDataSource() {
    return flyway.getConfiguration().getDataSource();
  }

  /** A {@code wallet_app} connection with auto-commit off; closing it rolls back open work. */
  protected Connection openTransaction() throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    return connection;
  }

  /** Runs {@code work} in one {@code wallet_app} transaction and commits it. */
  protected void inTransaction(SqlWork work) throws SQLException {
    try (Connection connection = openTransaction()) {
      work.run(connection);
      connection.commit();
    }
  }

  @FunctionalInterface
  protected interface SqlWork {
    void run(Connection connection) throws SQLException;
  }

  /**
   * Test only: runs {@code work} in auto-commit mode as the bootstrap superuser with {@code
   * session_replication_role = replica}, which disables triggers and foreign keys (CHECK
   * constraints still apply). For states {@code wallet_app} can no longer create, such as an
   * overdue hold. Committed rows stay: nothing can delete them.
   */
  protected static void withTriggersDisabled(SqlWork work) throws SQLException {
    try (Connection superuser = PostgresTestDatabase.openBootstrapSuperuserConnection()) {
      update(superuser, "SET session_replication_role = replica");
      work.run(superuser);
    }
  }

  /** Test only: commits one row without firing triggers; see {@link #withTriggersDisabled}. */
  protected static void insertWithTriggersDisabled(String table, Map<String, Object> values)
      throws SQLException {
    withTriggersDisabled(superuser -> insert(superuser, table, values));
  }

  // Generic row insert -----------------------------------------------------------------------

  /** An ordered column → value map; a {@code null} value inserts SQL NULL. */
  protected static Map<String, Object> row(Object... columnsAndValues) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < columnsAndValues.length; i += 2) {
      row.put((String) columnsAndValues[i], columnsAndValues[i + 1]);
    }
    return row;
  }

  /** A copy of {@code base} with one column replaced. */
  protected static Map<String, Object> with(Map<String, Object> base, String column, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(base);
    copy.put(column, value);
    return copy;
  }

  /** Inserts one row in auto-commit mode as {@code wallet_app}. */
  protected void insert(String table, Map<String, Object> values) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      insert(connection, table, values);
    }
  }

  /** Inserts one row on {@code connection}; table and column names are test constants. */
  protected static void insert(Connection connection, String table, Map<String, Object> values)
      throws SQLException {
    String columns = String.join(", ", values.keySet());
    String placeholders = values.keySet().stream().map(c -> "?").collect(Collectors.joining(", "));
    String sql = "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (Object value : values.values()) {
        statement.setObject(index++, value);
      }
      statement.executeUpdate();
    }
  }

  protected static int update(Connection connection, String sql, Object... args)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      return statement.executeUpdate();
    }
  }

  protected static <T> T queryOne(Connection connection, String sql, Class<T> type, Object... args)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("no row for: " + sql);
        }
        return rs.getObject(1, type);
      }
    }
  }

  /** The current transaction's {@code now()}, which the timestamp triggers use. */
  protected static OffsetDateTime transactionNow(Connection connection) throws SQLException {
    return queryOne(connection, "SELECT now()", OffsetDateTime.class);
  }

  // Wallet fixtures ---------------------------------------------------------------------------

  protected static String randomWalletNumber() {
    return String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
  }

  protected static Map<String, Object> validWallet() {
    return row(
        "id", UUID.randomUUID(),
        "wallet_number", randomWalletNumber(),
        "customer_id", UUID.randomUUID(),
        "tier", "BASIC",
        "currency", THB);
  }

  /** Inserts a valid wallet (balance 0) and returns its id. */
  protected UUID newWallet() throws SQLException {
    Map<String, Object> wallet = validWallet();
    insert("wallet.wallets", wallet);
    return (UUID) wallet.get("id");
  }

  protected UUID customerOfWallet(UUID walletId) {
    return jdbc.queryForObject(
        "SELECT customer_id FROM wallet.wallets WHERE id = ?", UUID.class, walletId);
  }

  /**
   * A valid completed deposit or withdrawal row for {@code walletId}, owned by the wallet's
   * customer (the composite foreign key requires it) and with a fresh idempotency key.
   */
  protected Map<String, Object> validMoneyMovement(UUID walletId) {
    return row(
        "id",
        UUID.randomUUID(),
        "wallet_id",
        walletId,
        "customer_id",
        customerOfWallet(walletId),
        "amount",
        new BigDecimal("100.00"),
        "currency",
        THB,
        "status",
        "COMPLETED",
        "idempotency_key",
        UUID.randomUUID(),
        "reference",
        "REF-" + UUID.randomUUID(),
        "limit_date",
        LocalDate.now());
  }

  // Ledger fixtures ---------------------------------------------------------------------------

  protected UUID systemAccountId(String code) {
    return jdbc.queryForObject(
        "SELECT id FROM ledger.ledger_accounts WHERE code = ?", UUID.class, code);
  }

  protected UUID cashClearingAccountId() {
    return systemAccountId("SYS-CASH-CLEARING");
  }

  /** Inserts an ACTIVE customer ledger account for a fresh wallet id and returns its id. */
  protected UUID newCustomerLedgerAccount() throws SQLException {
    UUID id = UUID.randomUUID();
    insert(
        "ledger.ledger_accounts",
        row(
            "id",
            id,
            "account_type",
            "CUSTOMER",
            "owner_reference",
            UUID.randomUUID(),
            "currency",
            THB));
    return id;
  }

  /** Inserts a financial transaction on {@code connection} and returns its id. */
  protected static UUID insertFinancialTransaction(Connection connection, String type)
      throws SQLException {
    UUID id = UUID.randomUUID();
    insert(
        connection,
        "ledger.financial_transactions",
        row(
            "id",
            id,
            "transaction_reference",
            "TX-" + UUID.randomUUID(),
            "transaction_type",
            type,
            "currency",
            THB));
    return id;
  }

  protected static void insertEntry(
      Connection connection, UUID transactionId, UUID accountId, String entryType, String amount)
      throws SQLException {
    insert(
        connection,
        "ledger.ledger_entries",
        row(
            "transaction_id",
            transactionId,
            "account_id",
            accountId,
            "entry_type",
            entryType,
            "amount",
            new BigDecimal(amount),
            "currency",
            THB));
  }

  /**
   * Commits a deposit-like posting: DEBIT SYS-CASH-CLEARING, CREDIT the customer account (doc 01
   * §6.2), and returns the financial transaction id.
   */
  protected UUID postDeposit(UUID customerAccountId, String amount) throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID[] transactionId = new UUID[1];
    inTransaction(
        c -> {
          transactionId[0] = insertFinancialTransaction(c, "DEPOSIT");
          insertEntry(c, transactionId[0], clearing, "DEBIT", amount);
          insertEntry(c, transactionId[0], customerAccountId, "CREDIT", amount);
        });
    return transactionId[0];
  }

  // Transfer fixtures -------------------------------------------------------------------------

  /**
   * A valid transfer row in {@code status}: COMPLETED rows carry {@code ledger_status = POSTED},
   * PENDING_REVIEW rows carry a review deadline; {@code held_at} is set by the database (V4).
   */
  protected static Map<String, Object> validTransfer(String status) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Map<String, Object> transfer =
        row(
            "id", UUID.randomUUID(),
            "reference", "TRF-" + UUID.randomUUID(),
            "sender_customer_id", UUID.randomUUID(),
            "receiver_customer_id", UUID.randomUUID(),
            "sender_wallet_id", UUID.randomUUID(),
            "receiver_wallet_id", UUID.randomUUID(),
            "amount", new BigDecimal("100.00"),
            "currency", THB,
            "description", "rent",
            "status", status,
            "idempotency_key", UUID.randomUUID(),
            "limit_date", LocalDate.now(),
            "risk_assessment_id", UUID.randomUUID());
    if ("COMPLETED".equals(status)) {
      transfer.put("ledger_status", "POSTED");
    }
    if ("PENDING_REVIEW".equals(status)) {
      transfer.put("review_expires_at", now.plusDays(1));
    }
    return transfer;
  }

  protected UUID newTransfer(String status) throws SQLException {
    Map<String, Object> transfer = validTransfer(status);
    insert("transfer.transfers", transfer);
    return (UUID) transfer.get("id");
  }

  // Risk fixtures -----------------------------------------------------------------------------

  protected static Map<String, Object> validRiskAssessment() {
    return row(
        "id",
        UUID.randomUUID(),
        "transfer_id",
        UUID.randomUUID(),
        "customer_id",
        UUID.randomUUID(),
        "amount",
        new BigDecimal("100.00"),
        "currency",
        THB,
        "score",
        0,
        "decision",
        "ALLOW");
  }

  protected UUID newRiskAssessment() throws SQLException {
    Map<String, Object> assessment = validRiskAssessment();
    insert("risk.risk_assessments", assessment);
    return (UUID) assessment.get("id");
  }

  protected UUID transferOfAssessment(UUID assessmentId) {
    return jdbc.queryForObject(
        "SELECT transfer_id FROM risk.risk_assessments WHERE id = ?", UUID.class, assessmentId);
  }

  /** A review row for {@code assessmentId}, naming the assessment's transfer (composite FK). */
  protected Map<String, Object> validRiskReview(UUID assessmentId, String action, UUID actor) {
    return row(
        "id", UUID.randomUUID(),
        "transfer_id", transferOfAssessment(assessmentId),
        "assessment_id", assessmentId,
        "action", action,
        "actor_user_id", actor);
  }

  // Concurrency -------------------------------------------------------------------------------

  /** Polls pg_stat_activity until backend {@code pid} waits on a lock, or fails after 20 s. */
  protected void awaitLockWait(int pid) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      String waitEventType =
          jdbc.query(
              "SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?",
              rs -> rs.next() ? rs.getString(1) : null,
              pid);
      if ("Lock".equals(waitEventType)) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("backend " + pid + " never waited on a lock");
  }
}
