package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.CHECK_VIOLATION;
import static com.portfolio.wallet.support.SqlAssertions.LOCK_NOT_AVAILABLE;
import static com.portfolio.wallet.support.SqlAssertions.RESTRICT_VIOLATION;
import static com.portfolio.wallet.support.SqlAssertions.assertCheckViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertRestrictViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertSqlState;
import static com.portfolio.wallet.support.SqlAssertions.assertUniqueViolation;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Double-entry ledger (documentations/01 §6.5; doc 06 MVP-ADR-02 "Ledger entries", MVP-ADR-03;
 * acceptance criteria 4 and 8, test cases 4, 5 and 8). Every statement runs as {@code wallet_app}.
 */
class LedgerIntegrityIT extends DatabaseIntegrationTest {

  // Balance check at commit (criterion 4) -----------------------------------------------------

  @Test
  void balancedTwoEntryTransactionCommits() throws SQLException {
    UUID customer = newCustomerLedgerAccount();

    UUID transaction = postDeposit(customer, "250.00");

    Map<String, Object> totals =
        jdbc.queryForMap(
            "SELECT count(*) AS entries,"
                + " sum(amount) FILTER (WHERE entry_type = 'DEBIT') AS debit,"
                + " sum(amount) FILTER (WHERE entry_type = 'CREDIT') AS credit"
                + " FROM ledger.ledger_entries WHERE transaction_id = ?",
            transaction);
    assertThat(totals.get("entries")).isEqualTo(2L);
    assertThat((BigDecimal) totals.get("debit")).isEqualByComparingTo("250.00");
    assertThat((BigDecimal) totals.get("credit")).isEqualByComparingTo("250.00");
  }

  @Test
  void unbalancedTransactionFailsAtCommit() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    UUID transaction;
    try (Connection c = openTransaction()) {
      transaction = insertFinancialTransaction(c, "DEPOSIT");
      // Both inserts succeed: the check is deferred to COMMIT.
      insertEntry(c, transaction, clearing, "DEBIT", "100.00");
      insertEntry(c, transaction, customer, "CREDIT", "99.99");

      SQLException error = assertSqlState(CHECK_VIOLATION, c::commit);
      assertThat(error.getMessage()).contains("is unbalanced");
    }
    assertNothingPersisted(transaction);
  }

  @Test
  void singleEntryTransactionFailsAtCommit() throws SQLException {
    UUID customer = newCustomerLedgerAccount();
    UUID transaction;
    try (Connection c = openTransaction()) {
      transaction = insertFinancialTransaction(c, "DEPOSIT");
      insertEntry(c, transaction, customer, "CREDIT", "100.00");

      SQLException error = assertSqlState(CHECK_VIOLATION, c::commit);
      assertThat(error.getMessage()).contains("at least 2 are required");
    }
    assertNothingPersisted(transaction);
  }

  @Test
  void transactionWithoutEntriesFailsAtCommit() throws SQLException {
    UUID transaction;
    try (Connection c = openTransaction()) {
      transaction = insertFinancialTransaction(c, "DEPOSIT");

      SQLException error = assertSqlState(CHECK_VIOLATION, c::commit);
      assertThat(error.getMessage()).contains("has 0 entries");
    }
    assertNothingPersisted(transaction);
  }

  @Test
  void twoDebitsWithoutCreditFailAtCommit() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    try (Connection c = openTransaction()) {
      UUID transaction = insertFinancialTransaction(c, "TRANSFER");
      insertEntry(c, transaction, clearing, "DEBIT", "50.00");
      insertEntry(c, transaction, customer, "DEBIT", "50.00");

      assertSqlState(CHECK_VIOLATION, c::commit);
    }
  }

  // Entry rules (criterion 4) -----------------------------------------------------------------

  @ParameterizedTest(name = "amount {0}")
  @ValueSource(strings = {"0.00", "-0.01", "-100.00"})
  void entryAmountMustBePositive(String amount) throws SQLException {
    UUID customer = newCustomerLedgerAccount();
    try (Connection c = openTransaction()) {
      UUID transaction = insertFinancialTransaction(c, "DEPOSIT");

      assertCheckViolation(
          "ck_ledger_entries_amount",
          () -> insertEntry(c, transaction, customer, "CREDIT", amount));
    }
  }

  static Stream<Arguments> invalidEntryColumns() {
    return Stream.of(
        Arguments.of("entry_type", "REFUND", "ck_ledger_entries_entry_type"),
        Arguments.of("currency", "USD", "ck_ledger_entries_currency"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidEntryColumns")
  void entryChecksRejectInvalidValues(String column, Object value, String constraint)
      throws SQLException {
    UUID customer = newCustomerLedgerAccount();
    try (Connection c = openTransaction()) {
      UUID transaction = insertFinancialTransaction(c, "DEPOSIT");
      Map<String, Object> entry =
          row(
              "transaction_id",
              transaction,
              "account_id",
              customer,
              "entry_type",
              "CREDIT",
              "amount",
              new BigDecimal("1.00"),
              "currency",
              THB);

      assertCheckViolation(
          constraint, () -> insert(c, "ledger.ledger_entries", with(entry, column, value)));
    }
  }

  static Stream<Arguments> invalidTransactionColumns() {
    return Stream.of(
        Arguments.of("transaction_type", "REFUND", "ck_financial_transactions_type"),
        Arguments.of("currency", "USD", "ck_financial_transactions_currency"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidTransactionColumns")
  void financialTransactionChecksRejectInvalidValues(
      String column, Object value, String constraint) {
    Map<String, Object> transaction =
        row(
            "transaction_reference",
            "TX-" + UUID.randomUUID(),
            "transaction_type",
            "DEPOSIT",
            "currency",
            THB);

    assertCheckViolation(
        constraint,
        () -> insert("ledger.financial_transactions", with(transaction, column, value)));
  }

  @Test
  void transactionReferenceIsUnique() throws SQLException {
    UUID transaction = postDeposit(newCustomerLedgerAccount(), "1.00");
    String reference =
        jdbc.queryForObject(
            "SELECT transaction_reference FROM ledger.financial_transactions WHERE id = ?",
            String.class,
            transaction);

    assertUniqueViolation(
        "uq_financial_transactions_reference",
        () ->
            insert(
                "ledger.financial_transactions",
                row(
                    "transaction_reference",
                    reference,
                    "transaction_type",
                    "DEPOSIT",
                    "currency",
                    THB)));
  }

  // A posted transaction never gains entries (MVP-ADR-02) -------------------------------------

  @Test
  void committedTransactionRejectsALaterBalancedPair() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    UUID transaction = postDeposit(customer, "100.00");

    try (Connection c = openTransaction()) {
      assertRestrictViolation(
          "does not belong to this database transaction",
          () -> {
            insertEntry(c, transaction, clearing, "DEBIT", "1000.00");
            insertEntry(c, transaction, customer, "CREDIT", "1000.00");
          });
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE transaction_id = ?",
                Integer.class,
                transaction))
        .isEqualTo(2);
  }

  @Test
  void entryForAMissingHeaderIsRefusedByTheGuard() throws SQLException {
    UUID customer = newCustomerLedgerAccount();
    UUID missing = UUID.randomUUID();

    try (Connection c = openTransaction()) {
      // The guard runs before the foreign key, so the outcome is restrict_violation.
      assertRestrictViolation(
          "does not belong to this database transaction",
          () -> insertEntry(c, missing, customer, "CREDIT", "10.00"));
    }
  }

  @Test
  void anotherSessionCannotJoinAnUncommittedHeader() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    UUID transaction;
    try (Connection a = openTransaction();
        Connection b = openTransaction()) {
      transaction = insertFinancialTransaction(a, "DEPOSIT");
      insertEntry(a, transaction, clearing, "DEBIT", "10.00");
      insertEntry(a, transaction, customer, "CREDIT", "10.00");

      // Refused while A is still open: a wait on A would end in LOCK_NOT_AVAILABLE instead.
      update(b, "SET LOCAL lock_timeout = '300ms'");
      UUID header = transaction;
      assertRestrictViolation(
          "does not belong to this database transaction",
          () -> insertEntry(b, header, clearing, "DEBIT", "1000.00"));
      b.rollback();

      a.commit();
    }

    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM ledger.ledger_entries WHERE transaction_id = ?",
                BigDecimal.class,
                transaction))
        .hasSize(2)
        .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("10.00"));
  }

  /**
   * Simulates the collision a logical restore makes possible: a committed header whose created_xid
   * equals the running transaction's xid, with an older created_at.
   */
  @Test
  void headerWithTheCurrentXidButAnOlderCreatedAtIsRefused() throws SQLException {
    UUID customer = newCustomerLedgerAccount();
    UUID header = UUID.randomUUID();
    try (Connection c = openTransaction()) {
      String xid = queryOne(c, "SELECT pg_current_xact_id()::text", String.class);
      withTriggersDisabled(
          superuser ->
              update(
                  superuser,
                  "INSERT INTO ledger.financial_transactions"
                      + " (id, transaction_reference, transaction_type, currency, created_at,"
                      + " created_xid) VALUES (?, ?, 'DEPOSIT', 'THB', ?, ?::xid8)",
                  header,
                  "TX-" + UUID.randomUUID(),
                  BACKDATED,
                  xid));

      assertRestrictViolation(
          "does not belong to this database transaction",
          () -> insertEntry(c, header, customer, "CREDIT", "10.00"));
    }
  }

  @Test
  void postingInsideASavepointIsAccepted() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    UUID transaction;
    try (Connection c = openTransaction()) {
      // created_xid is the top-level transaction id (pg_current_xact_id() inside a savepoint
      // too), so a header created in a savepoint still belongs to this transaction.
      Savepoint savepoint = c.setSavepoint();
      transaction = insertFinancialTransaction(c, "DEPOSIT");
      insertEntry(c, transaction, clearing, "DEBIT", "10.00");
      insertEntry(c, transaction, customer, "CREDIT", "10.00");
      c.releaseSavepoint(savepoint);
      c.commit();
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE transaction_id = ?",
                Integer.class,
                transaction))
        .isEqualTo(2);
  }

  @Test
  void clientSuppliedCreatedXidIsOverwritten() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    UUID transaction = UUID.randomUUID();
    String[] currentXid = new String[1];

    inTransaction(
        c -> {
          update(
              c,
              "INSERT INTO ledger.financial_transactions"
                  + " (id, transaction_reference, transaction_type, currency, created_xid)"
                  + " VALUES (?, ?, 'DEPOSIT', 'THB', '3'::xid8)",
              transaction,
              "TX-" + UUID.randomUUID());
          currentXid[0] = queryOne(c, "SELECT pg_current_xact_id()::text", String.class);
          // Accepted: the guard compares against the overwritten value, not the sent one.
          insertEntry(c, transaction, clearing, "DEBIT", "10.00");
          insertEntry(c, transaction, customer, "CREDIT", "10.00");
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT created_xid::text FROM ledger.financial_transactions WHERE id = ?",
                String.class,
                transaction))
        .isEqualTo(currentXid[0])
        .isNotEqualTo("3");
  }

  @Test
  void entriesAddedInASavepointToThisTransactionsPostingAreAccepted() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    UUID transaction;
    try (Connection c = openTransaction()) {
      transaction = insertFinancialTransaction(c, "DEPOSIT");
      Savepoint savepoint = c.setSavepoint();
      insertEntry(c, transaction, clearing, "DEBIT", "10.00");
      insertEntry(c, transaction, customer, "CREDIT", "10.00");
      c.releaseSavepoint(savepoint);
      c.commit();
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE transaction_id = ?",
                Integer.class,
                transaction))
        .isEqualTo(2);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"FROZEN", "CLOSED"})
  void entryOnInactiveAccountIsRejected(String status) throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    jdbc.update("UPDATE ledger.ledger_accounts SET status = ? WHERE id = ?", status, customer);

    try (Connection c = openTransaction()) {
      UUID transaction = insertFinancialTransaction(c, "DEPOSIT");
      insertEntry(c, transaction, clearing, "DEBIT", "10.00");

      assertRestrictViolation(
          "is " + status + "; it accepts no new entries",
          () -> insertEntry(c, transaction, customer, "CREDIT", "10.00"));
    }
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"SYSTEM,FROZEN", "SYSTEM,CLOSED", "SUSPENSE,FROZEN", "SUSPENSE,CLOSED"})
  void entryOnInactiveSystemOrSuspenseAccountIsRejected(String type, String status)
      throws SQLException {
    // A test-created account: the seed rows must stay ACTIVE and accounts cannot be deleted.
    UUID account = UUID.randomUUID();
    insert(
        "ledger.ledger_accounts",
        row(
            "id",
            account,
            "code",
            "TEST-" + UUID.randomUUID(),
            "account_type",
            type,
            "currency",
            THB,
            "status",
            status));

    try (Connection c = openTransaction()) {
      UUID transaction = insertFinancialTransaction(c, "DEPOSIT");

      assertRestrictViolation(
          "is " + status + "; it accepts no new entries",
          () -> insertEntry(c, transaction, account, "DEBIT", "10.00"));
    }
  }

  // Customer account status is read FOR SHARE; system accounts are not locked ------------------

  @Test
  void entryOnACustomerAccountWaitsForAConcurrentFreeze() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    try (Connection freeze = openTransaction();
        Connection posting = openTransaction()) {
      update(freeze, "UPDATE ledger.ledger_accounts SET status = 'FROZEN' WHERE id = ?", customer);

      update(posting, "SET LOCAL lock_timeout = '300ms'");
      UUID transaction = insertFinancialTransaction(posting, "DEPOSIT");
      insertEntry(posting, transaction, clearing, "DEBIT", "10.00");

      // Without FOR SHARE the entry would read the old ACTIVE status and be accepted.
      assertSqlState(
          LOCK_NOT_AVAILABLE, () -> insertEntry(posting, transaction, customer, "CREDIT", "10.00"));
    }
  }

  @Test
  void entryWaitingOnAFreezeIsRejectedOnceTheFreezeCommits() throws Exception {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection freeze = openTransaction();
        Connection posting = openTransaction()) {
      update(freeze, "UPDATE ledger.ledger_accounts SET status = 'FROZEN' WHERE id = ?", customer);
      int postingPid = queryOne(posting, "SELECT pg_backend_pid()", Integer.class);
      UUID transaction = insertFinancialTransaction(posting, "DEPOSIT");
      insertEntry(posting, transaction, clearing, "DEBIT", "10.00");

      Future<SQLException> entry =
          executor.submit(
              () ->
                  assertSqlState(
                      RESTRICT_VIOLATION,
                      () -> insertEntry(posting, transaction, customer, "CREDIT", "10.00")));

      awaitLockWait(postingPid);
      freeze.commit();

      assertThat(entry.get(20, TimeUnit.SECONDS).getMessage())
          .contains("is FROZEN; it accepts no new entries");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void entryOnASystemAccountTakesNoRowLock() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    try (Connection holder = openTransaction();
        Connection posting = openTransaction()) {
      // Holds a row lock on SYS-CASH-CLEARING that FOR SHARE would conflict with; rolled back.
      update(holder, "UPDATE ledger.ledger_accounts SET status = status WHERE id = ?", clearing);

      update(posting, "SET LOCAL lock_timeout = '300ms'");
      UUID transaction = insertFinancialTransaction(posting, "DEPOSIT");
      insertEntry(posting, transaction, clearing, "DEBIT", "10.00");
      insertEntry(posting, transaction, customer, "CREDIT", "10.00");
      posting.commit();

      holder.rollback();
    }
  }

  // Timestamps are set by the database (MVP-ADR-02) -------------------------------------------

  @Test
  void clientSuppliedCreatedAtIsOverwrittenOnLedgerAccounts() throws SQLException {
    UUID account = UUID.randomUUID();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(
              c,
              "ledger.ledger_accounts",
              row(
                  "id",
                  account,
                  "account_type",
                  "CUSTOMER",
                  "owner_reference",
                  UUID.randomUUID(),
                  "currency",
                  THB,
                  "created_at",
                  BACKDATED));
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT created_at FROM ledger.ledger_accounts WHERE id = ?",
                OffsetDateTime.class,
                account))
        .isEqualTo(now[0]);
  }

  @Test
  void clientSuppliedCreatedAtIsOverwrittenOnLedgerRows() throws SQLException {
    UUID clearing = cashClearingAccountId();
    UUID customer = newCustomerLedgerAccount();
    UUID transaction = UUID.randomUUID();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(
              c,
              "ledger.financial_transactions",
              row(
                  "id",
                  transaction,
                  "transaction_reference",
                  "TX-" + UUID.randomUUID(),
                  "transaction_type",
                  "DEPOSIT",
                  "currency",
                  THB,
                  "created_at",
                  BACKDATED));
          for (Object[] leg : new Object[][] {{clearing, "DEBIT"}, {customer, "CREDIT"}}) {
            insert(
                c,
                "ledger.ledger_entries",
                row(
                    "transaction_id",
                    transaction,
                    "account_id",
                    leg[0],
                    "entry_type",
                    leg[1],
                    "amount",
                    new BigDecimal("5.00"),
                    "currency",
                    THB,
                    "created_at",
                    BACKDATED));
          }
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT created_at FROM ledger.financial_transactions WHERE id = ?",
                OffsetDateTime.class,
                transaction))
        .isEqualTo(now[0]);
    assertThat(
            jdbc.queryForList(
                "SELECT created_at FROM ledger.ledger_entries WHERE transaction_id = ?",
                OffsetDateTime.class,
                transaction))
        .hasSize(2)
        .containsOnly(now[0]);
  }

  // Ledger accounts ---------------------------------------------------------------------------

  static Stream<Arguments> invalidAccounts() {
    UUID owner = UUID.randomUUID();
    return Stream.of(
        Arguments.of(
            "unknown account type",
            row("account_type", "MERCHANT", "code", "SYS-X", "currency", THB),
            "ck_ledger_accounts_account_type"),
        Arguments.of(
            "customer account without owner",
            row("account_type", "CUSTOMER", "currency", THB),
            "ck_ledger_accounts_customer_owner"),
        Arguments.of(
            "system account with owner",
            row(
                "account_type",
                "SYSTEM",
                "code",
                "SYS-X-" + owner,
                "owner_reference",
                owner,
                "currency",
                THB),
            "ck_ledger_accounts_customer_owner"),
        Arguments.of(
            "system account without code",
            row("account_type", "SYSTEM", "currency", THB),
            "ck_ledger_accounts_system_code"),
        Arguments.of(
            "customer account with code",
            row(
                "account_type",
                "CUSTOMER",
                "owner_reference",
                owner,
                "code",
                "CUST-1",
                "currency",
                THB),
            "ck_ledger_accounts_system_code"),
        Arguments.of(
            "currency other than THB",
            row("account_type", "CUSTOMER", "owner_reference", owner, "currency", "USD"),
            "ck_ledger_accounts_currency"),
        Arguments.of(
            "unknown status",
            row(
                "account_type",
                "CUSTOMER",
                "owner_reference",
                owner,
                "currency",
                THB,
                "status",
                "DELETED"),
            "ck_ledger_accounts_status"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidAccounts")
  void ledgerAccountChecksRejectInvalidRows(
      String description, Map<String, Object> account, String constraint) {
    assertCheckViolation(constraint, () -> insert("ledger.ledger_accounts", account));
  }

  @Test
  void systemAccountCodeIsUnique() {
    assertUniqueViolation(
        "uq_ledger_accounts_code",
        () ->
            insert(
                "ledger.ledger_accounts",
                row("account_type", "SYSTEM", "code", "SYS-CASH-CLEARING", "currency", THB)));
  }

  @Test
  void aWalletHasOneCustomerLedgerAccount() throws SQLException {
    UUID wallet = UUID.randomUUID();
    Map<String, Object> account =
        row("account_type", "CUSTOMER", "owner_reference", wallet, "currency", THB);
    insert("ledger.ledger_accounts", account);

    assertUniqueViolation(
        "uq_ledger_accounts_customer_owner", () -> insert("ledger.ledger_accounts", account));
  }

  // Seed (criterion 8) ------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"SYS-CASH-CLEARING:SYSTEM", "SYS-SUSPENSE:SUSPENSE"})
  void systemAccountIsSeededOnceWithItsType(String codeAndType) {
    String code = codeAndType.split(":")[0];
    String type = codeAndType.split(":")[1];

    assertThat(
            jdbc.queryForList(
                "SELECT account_type, currency, status, owner_reference"
                    + " FROM ledger.ledger_accounts WHERE code = ?",
                code))
        .singleElement()
        .satisfies(
            account -> {
              assertThat(account.get("account_type")).isEqualTo(type);
              assertThat(account.get("currency")).isEqualTo(THB);
              assertThat(account.get("status")).isEqualTo("ACTIVE");
              assertThat(account.get("owner_reference")).isNull();
            });
  }

  private void assertNothingPersisted(UUID transaction) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.financial_transactions WHERE id = ?",
                Integer.class,
                transaction))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE transaction_id = ?",
                Integer.class,
                transaction))
        .isZero();
  }
}
