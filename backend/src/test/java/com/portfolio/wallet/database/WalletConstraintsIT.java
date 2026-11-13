package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.assertCheckViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertForeignKeyViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertRestrictViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertUniqueViolation;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Wallet module constraints (documentations/06 MVP-ADR-08, -18, -22; doc 08 Q3; acceptance criteria
 * 5 and 7, test cases 5 and 7). Every statement runs as {@code wallet_app}.
 */
class WalletConstraintsIT extends DatabaseIntegrationTest {

  // Wallets (criterion 5) ---------------------------------------------------------------------

  @Test
  void validWalletIsAccepted() throws SQLException {
    UUID wallet = newWallet();

    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT status, available_balance, reserved_balance FROM wallet.wallets WHERE id = ?",
            wallet);
    assertThat(stored.get("status")).isEqualTo("ACTIVE");
    assertThat((BigDecimal) stored.get("available_balance")).isEqualByComparingTo("0");
    assertThat((BigDecimal) stored.get("reserved_balance")).isEqualByComparingTo("0");
  }

  static Stream<Arguments> invalidWalletColumns() {
    return Stream.of(
        Arguments.of("available_balance", new BigDecimal("-0.01"), "ck_wallets_available_balance"),
        Arguments.of("reserved_balance", new BigDecimal("-0.01"), "ck_wallets_reserved_balance"),
        Arguments.of("currency", "USD", "ck_wallets_currency"),
        Arguments.of("tier", "GOLD", "ck_wallets_tier"),
        Arguments.of("status", "DELETED", "ck_wallets_status"),
        Arguments.of("wallet_number", "1234567890", "ck_wallets_wallet_number"),
        Arguments.of("wallet_number", "1234567890A", "ck_wallets_wallet_number"),
        Arguments.of("wallet_number", "-1234567890", "ck_wallets_wallet_number"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidWalletColumns")
  void walletChecksRejectInvalidValues(String column, Object value, String constraint) {
    assertCheckViolation(
        constraint, () -> insert("wallet.wallets", with(validWallet(), column, value)));
  }

  @Test
  void overdraftByUpdateIsRejected() throws SQLException {
    UUID wallet = newWallet();

    assertCheckViolation(
        "ck_wallets_available_balance",
        () ->
            jdbc.update(
                "UPDATE wallet.wallets SET available_balance = available_balance - 0.01"
                    + " WHERE id = ?",
                wallet));
  }

  @Test
  void negativeReservedBalanceByUpdateIsRejected() throws SQLException {
    UUID wallet = newWallet();

    assertCheckViolation(
        "ck_wallets_reserved_balance",
        () ->
            jdbc.update(
                "UPDATE wallet.wallets SET reserved_balance = reserved_balance - 0.01"
                    + " WHERE id = ?",
                wallet));
  }

  @Test
  void secondWalletForTheSameCustomerIsRejected() throws SQLException {
    Map<String, Object> first = validWallet();
    insert("wallet.wallets", first);

    Map<String, Object> second = with(validWallet(), "customer_id", first.get("customer_id"));

    assertUniqueViolation("uq_wallets_customer", () -> insert("wallet.wallets", second));
  }

  @Test
  void walletNumberIsUnique() throws SQLException {
    Map<String, Object> first = validWallet();
    insert("wallet.wallets", first);

    assertUniqueViolation(
        "uq_wallets_wallet_number",
        () ->
            insert(
                "wallet.wallets",
                with(validWallet(), "wallet_number", first.get("wallet_number"))));
  }

  static Stream<Arguments> immutableWalletColumns() {
    return Stream.of(
        Arguments.of("id", UUID.randomUUID()),
        Arguments.of("wallet_number", randomWalletNumber()),
        Arguments.of("customer_id", UUID.randomUUID()),
        Arguments.of("currency", "USD"),
        Arguments.of("created_at", BACKDATED));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("immutableWalletColumns")
  void walletIdentityCannotChange(String column, Object value) throws SQLException {
    UUID wallet = newWallet();

    assertRestrictViolation(
        "are immutable",
        () ->
            jdbc.update(
                "UPDATE wallet.wallets SET " + column + " = ? WHERE id = ?", value, wallet));
  }

  @Test
  void clientSuppliedWalletTimestampsAreOverwritten() throws SQLException {
    Map<String, Object> wallet =
        with(with(validWallet(), "created_at", BACKDATED), "updated_at", BACKDATED);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, "wallet.wallets", wallet);
        });

    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT created_at = ? AS created, updated_at = ? AS updated"
                + " FROM wallet.wallets WHERE id = ?",
            now[0],
            now[0],
            wallet.get("id"));
    assertThat(stored.get("created")).isEqualTo(true);
    assertThat(stored.get("updated")).isEqualTo(true);
  }

  @Test
  void updateRefreshesUpdatedAtEvenIfTheClientSetsIt() throws SQLException {
    UUID wallet = newWallet();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          update(
              c,
              "UPDATE wallet.wallets SET tier = 'VERIFIED', updated_at = ? WHERE id = ?",
              BACKDATED,
              wallet);
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at FROM wallet.wallets WHERE id = ?", OffsetDateTime.class, wallet))
        .isEqualTo(now[0]);
  }

  @ParameterizedTest(name = "CLOSED to {0}")
  @ValueSource(strings = {"ACTIVE", "FROZEN"})
  void closedWalletCannotChangeItsStatus(String status) throws SQLException {
    UUID wallet = newWallet();
    jdbc.update("UPDATE wallet.wallets SET status = 'CLOSED' WHERE id = ?", wallet);

    assertRestrictViolation(
        "is CLOSED; its status can no longer change",
        () -> jdbc.update("UPDATE wallet.wallets SET status = ? WHERE id = ?", status, wallet));
  }

  @Test
  void closedWalletCanStillReleaseAHold() throws SQLException {
    UUID wallet = newWallet();
    jdbc.update(
        "UPDATE wallet.wallets SET reserved_balance = 40.00, status = 'CLOSED' WHERE id = ?",
        wallet);

    assertThat(
            jdbc.update(
                "UPDATE wallet.wallets SET reserved_balance = 0, available_balance = 40.00,"
                    + " version = version + 1 WHERE id = ?",
                wallet))
        .isEqualTo(1);
  }

  @Test
  void balanceStatusAndTierCanChange() throws SQLException {
    UUID wallet = newWallet();

    assertThat(
            jdbc.update(
                "UPDATE wallet.wallets SET available_balance = 10.00, reserved_balance = 5.00,"
                    + " status = 'FROZEN', tier = 'VERIFIED', version = version + 1 WHERE id = ?",
                wallet))
        .isEqualTo(1);
  }

  // Deposits and withdrawals ------------------------------------------------------------------

  static Stream<Arguments> invalidMoneyMovementColumns() {
    return Stream.of("wallet.deposits", "wallet.withdrawals")
        .flatMap(
            table -> {
              String prefix = "ck_" + table.substring("wallet.".length()) + "_";
              return Stream.of(
                  Arguments.of(table, "currency", "USD", prefix + "currency"),
                  Arguments.of(table, "amount", new BigDecimal("0.00"), prefix + "amount"),
                  Arguments.of(table, "amount", new BigDecimal("-1.00"), prefix + "amount"),
                  Arguments.of(table, "status", "PENDING", prefix + "status"),
                  Arguments.of(table, "ledger_status", "PENDING_POST", prefix + "ledger_status"));
            });
  }

  @ParameterizedTest(name = "{0}: {1} = {2}")
  @MethodSource("invalidMoneyMovementColumns")
  void moneyMovementChecksRejectInvalidValues(
      String table, String column, Object value, String constraint) throws SQLException {
    UUID wallet = newWallet();

    assertCheckViolation(
        constraint, () -> insert(table, with(validMoneyMovement(wallet), column, value)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet.deposits", "wallet.withdrawals"})
  void moneyMovementIsPostedByDefault(String table) throws SQLException {
    Map<String, Object> movement = validMoneyMovement(newWallet());
    insert(table, movement);

    assertThat(
            jdbc.queryForObject(
                "SELECT ledger_status FROM " + table + " WHERE id = ?",
                String.class,
                movement.get("id")))
        .isEqualTo("POSTED");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet.deposits", "wallet.withdrawals"})
  void moneyMovementReferenceIsUnique(String table) throws SQLException {
    UUID wallet = newWallet();
    Map<String, Object> first = validMoneyMovement(wallet);
    insert(table, first);
    String constraint = "uq_" + table.substring("wallet.".length()) + "_reference";

    assertUniqueViolation(
        constraint,
        () -> insert(table, with(validMoneyMovement(wallet), "reference", first.get("reference"))));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet.deposits", "wallet.withdrawals"})
  void sameCustomerCannotReuseAnIdempotencyKey(String table) throws SQLException {
    UUID wallet = newWallet();
    Map<String, Object> first = validMoneyMovement(wallet);
    insert(table, first);
    String constraint = "uq_" + table.substring("wallet.".length()) + "_customer_idempotency_key";

    assertUniqueViolation(
        constraint,
        () ->
            insert(
                table,
                with(validMoneyMovement(wallet), "idempotency_key", first.get("idempotency_key"))));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet.deposits", "wallet.withdrawals"})
  void differentCustomersMayUseTheSameIdempotencyKey(String table) throws SQLException {
    UUID key = UUID.randomUUID();

    insert(table, with(validMoneyMovement(newWallet()), "idempotency_key", key));
    insert(table, with(validMoneyMovement(newWallet()), "idempotency_key", key));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT customer_id) FROM " + table + " WHERE idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(2);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet.deposits", "wallet.withdrawals"})
  void moneyMovementMustBelongToTheWalletsCustomer(String table) throws SQLException {
    UUID wallet = newWallet();
    String constraint = "fk_" + table.substring("wallet.".length()) + "_wallet";

    assertForeignKeyViolation(
        constraint,
        () -> insert(table, with(validMoneyMovement(wallet), "customer_id", UUID.randomUUID())));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet.deposits", "wallet.withdrawals"})
  void clientSuppliedCreatedAtIsOverwritten(String table) throws SQLException {
    Map<String, Object> movement = with(validMoneyMovement(newWallet()), "created_at", BACKDATED);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, table, movement);
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT created_at FROM " + table + " WHERE id = ?",
                OffsetDateTime.class,
                movement.get("id")))
        .isEqualTo(now[0]);
  }

  // Daily limit usage (criterion 7) -----------------------------------------------------------

  private static Map<String, Object> validUsage(UUID wallet) {
    return row("wallet_id", wallet, "operation", "TRANSFER", "limit_date", LocalDate.now());
  }

  static Stream<Arguments> invalidUsageColumns() {
    return Stream.of(
        Arguments.of("used", new BigDecimal("-0.01"), "ck_daily_limit_usage_used"),
        Arguments.of("reserved", new BigDecimal("-0.01"), "ck_daily_limit_usage_reserved"),
        Arguments.of("operation", "PAYMENT", "ck_daily_limit_usage_operation"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidUsageColumns")
  void dailyLimitUsageChecksRejectInvalidValues(String column, Object value, String constraint)
      throws SQLException {
    UUID wallet = newWallet();

    assertCheckViolation(
        constraint,
        () -> insert("wallet.daily_limit_usage", with(validUsage(wallet), column, value)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"used", "reserved"})
  void dailyLimitUsageCannotGoNegativeByUpdate(String column) throws SQLException {
    UUID wallet = newWallet();
    insert("wallet.daily_limit_usage", validUsage(wallet));

    assertCheckViolation(
        "ck_daily_limit_usage_" + column,
        () ->
            jdbc.update(
                "UPDATE wallet.daily_limit_usage SET "
                    + column
                    + " = "
                    + column
                    + " - 0.01"
                    + " WHERE wallet_id = ?",
                wallet));
  }

  @Test
  void dailyLimitUsageIsOneRowPerWalletOperationAndDay() throws SQLException {
    UUID wallet = newWallet();
    insert("wallet.daily_limit_usage", validUsage(wallet));

    assertUniqueViolation(
        "pk_daily_limit_usage", () -> insert("wallet.daily_limit_usage", validUsage(wallet)));
  }

  // Idempotency records -----------------------------------------------------------------------

  @Test
  void walletIdempotencyRecordAcceptsOnlyDepositAndWithdrawal() {
    assertCheckViolation(
        "ck_wallet_idempotency_records_operation",
        () ->
            insert(
                "wallet.idempotency_records",
                row(
                    "customer_id",
                    UUID.randomUUID(),
                    "operation",
                    "TRANSFER",
                    "idempotency_key",
                    UUID.randomUUID(),
                    "request_hash",
                    "a".repeat(64),
                    "expires_at",
                    OffsetDateTime.now().plusDays(1))));
  }

  // Lookup attempts ---------------------------------------------------------------------------

  @Test
  void clientSuppliedAttemptedAtIsOverwritten() throws SQLException {
    UUID user = UUID.randomUUID();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          try (PreparedStatement statement =
              c.prepareStatement(
                  "INSERT INTO wallet.lookup_attempts (user_id, client_ip, attempted_at)"
                      + " VALUES (?, '192.0.2.1'::inet, ?)")) {
            statement.setObject(1, user);
            statement.setObject(2, BACKDATED);
            statement.executeUpdate();
          }
        });

    // V10 (Y1): the start of the INSERT statement, which is at or after the transaction's start.
    OffsetDateTime stamped =
        jdbc.queryForObject(
            "SELECT attempted_at FROM wallet.lookup_attempts WHERE user_id = ?",
            OffsetDateTime.class,
            user);
    assertThat(stamped).isAfterOrEqualTo(now[0]).isNotEqualTo(BACKDATED);
  }

  @Test
  void attemptsAreStampedWithTheirStatementNotTheirTransaction() throws SQLException {
    UUID user = UUID.randomUUID();
    OffsetDateTime[] times = new OffsetDateTime[2];

    inTransaction(
        c -> {
          times[0] = transactionNow(c);
          queryOne(c, "SELECT pg_sleep(0.2)::text", String.class);
          update(
              c,
              "INSERT INTO wallet.lookup_attempts (user_id, client_ip) VALUES (?, '192.0.2.2')",
              user);
        });

    OffsetDateTime stamped =
        jdbc.queryForObject(
            "SELECT attempted_at FROM wallet.lookup_attempts WHERE user_id = ?",
            OffsetDateTime.class,
            user);
    assertThat(stamped).isAfter(times[0].plusNanos(150_000_000));
  }

  // V10 (Y4) and V11 (PR 5 plan Q2): idempotency records hold only states the application writes,
  // with the same checks on each money module's own table ------------------------------------

  private static final String[] BAD_REQUEST_HASHES = {
    "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg",
    ""
  };

  private static String idempotencyTable(String module) {
    return module + ".idempotency_records";
  }

  private static String idempotencyCheck(String module, String check) {
    return "ck_" + module + "_idempotency_records_" + check;
  }

  private static Map<String, Object> validIdempotencyRecord(String module) {
    OffsetDateTime created = OffsetDateTime.now();
    return row(
        "customer_id", UUID.randomUUID(),
        "operation", module.equals("wallet") ? "DEPOSIT" : "TRANSFER",
        "idempotency_key", UUID.randomUUID(),
        "request_hash", "0123456789abcdef".repeat(4),
        "created_at", created,
        "expires_at", created.plusDays(1));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet", "transfer"})
  void validIdempotencyRecordStatesAreAccepted(String module) throws SQLException {
    insert(idempotencyTable(module), validIdempotencyRecord(module));
    insert(
        idempotencyTable(module),
        with(
            with(validIdempotencyRecord(module), "response_status", 201),
            "response_body",
            jsonb("{\"id\": \"x\"}")));
    insert(
        idempotencyTable(module),
        with(
            with(validIdempotencyRecord(module), "response_status", 409),
            "response_body",
            jsonb("{\"errorCode\": \"INSUFFICIENT_BALANCE\", \"message\": \"m\"}")));
  }

  /** A stored risk block (403 RISK_BLOCKED) is a refusal like the others (PR 5 plan Q3). */
  @Test
  void aStoredRiskBlockIsAcceptedForTransfers() throws SQLException {
    insert(
        idempotencyTable("transfer"),
        with(
            with(validIdempotencyRecord("transfer"), "response_status", 403),
            "response_body",
            jsonb("{\"errorCode\": \"RISK_BLOCKED\", \"message\": \"m\"}")));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet", "transfer"})
  void anIdempotencyOutcomeHasBothStatusAndBodyOrNeither(String module) {
    assertCheckViolation(
        idempotencyCheck(module, "outcome"),
        () ->
            insert(
                idempotencyTable(module),
                with(validIdempotencyRecord(module), "response_status", 201)));
    assertCheckViolation(
        idempotencyCheck(module, "outcome"),
        () ->
            insert(
                idempotencyTable(module),
                with(validIdempotencyRecord(module), "response_body", jsonb("{}"))));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet", "transfer"})
  void anIdempotencyStatusIsAnHttpStatus(String module) {
    for (int status : new int[] {0, 199, 600, -1}) {
      assertCheckViolation(
          idempotencyCheck(module, "status"),
          () ->
              insert(
                  idempotencyTable(module),
                  with(
                      with(validIdempotencyRecord(module), "response_status", status),
                      "response_body",
                      jsonb("{}"))));
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet", "transfer"})
  void anIdempotencyRequestHashIsLowerCaseSha256Hex(String module) {
    for (String hash : BAD_REQUEST_HASHES) {
      assertCheckViolation(
          idempotencyCheck(module, "request_hash"),
          () ->
              insert(
                  idempotencyTable(module),
                  with(validIdempotencyRecord(module), "request_hash", hash)));
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"wallet", "transfer"})
  void anIdempotencyRecordExpiresAfterItWasCreated(String module) {
    Map<String, Object> record = validIdempotencyRecord(module);
    assertCheckViolation(
        idempotencyCheck(module, "expiry"),
        () ->
            insert(idempotencyTable(module), with(record, "expires_at", record.get("created_at"))));
  }

  private static org.postgresql.util.PGobject jsonb(String json) {
    try {
      org.postgresql.util.PGobject value = new org.postgresql.util.PGobject();
      value.setType("jsonb");
      value.setValue(json);
      return value;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void transferIdempotencyRecordAcceptsOnlyTransfer() {
    assertCheckViolation(
        "ck_transfer_idempotency_records_operation",
        () ->
            insert(
                "transfer.idempotency_records",
                row(
                    "customer_id",
                    UUID.randomUUID(),
                    "operation",
                    "DEPOSIT",
                    "idempotency_key",
                    UUID.randomUUID(),
                    "request_hash",
                    "a".repeat(64),
                    "expires_at",
                    OffsetDateTime.now().plusDays(1))));
  }
}
