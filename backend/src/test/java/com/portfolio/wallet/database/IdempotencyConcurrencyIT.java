package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.UNIQUE_VIOLATION;
import static com.portfolio.wallet.support.SqlAssertions.assertSqlState;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Concurrent idempotency-key claims (documentations/08 §4.5 test case 12, criterion 6; doc 02 §1.4;
 * doc 06 MVP-ADR-06). The primary key {@code (customer_id, operation, idempotency_key)} is the
 * final guard: of two concurrent inserts exactly one succeeds and the other gets a unique
 * violation. Runs as {@code wallet_app}, one connection per competitor.
 */
class IdempotencyConcurrencyIT extends DatabaseIntegrationTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private static Map<String, Object> idempotencyRecord(UUID customer, String operation, UUID key) {
    return row(
        "customer_id", customer,
        "operation", operation,
        "idempotency_key", key,
        "request_hash", "a".repeat(64),
        "expires_at", OffsetDateTime.now().plusDays(1));
  }

  /**
   * Deterministic interleaving: the first transaction inserts and stays open; the second insert is
   * observed waiting on the first one's lock; after the first commits, the second fails with 23505
   * instead of creating a duplicate.
   */
  @Test
  void secondClaimWaitsForTheFirstAndThenFailsWithUniqueViolation() throws Exception {
    Map<String, Object> claim = idempotencyRecord(UUID.randomUUID(), "DEPOSIT", UUID.randomUUID());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection first = openTransaction();
        Connection second = openTransaction()) {
      insert(first, "wallet.idempotency_records", claim);
      int secondPid = queryOne(second, "SELECT pg_backend_pid()", Integer.class);

      Future<SQLException> secondClaim =
          executor.submit(
              () ->
                  assertSqlState(
                      UNIQUE_VIOLATION,
                      () -> {
                        insert(second, "wallet.idempotency_records", claim);
                        second.commit();
                      }));

      awaitLockWait(secondPid);
      first.commit();

      SQLException error = secondClaim.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      assertThat(error.getMessage()).contains("\"pk_wallet_idempotency_records\"");
    } finally {
      executor.shutdownNow();
    }

    assertThat(countClaims("wallet.idempotency_records", claim)).isEqualTo(1);
  }

  /** Free-running race: both competitors start at the same moment, auto-commit. */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "wallet.idempotency_records, DEPOSIT",
    "wallet.idempotency_records, WITHDRAWAL",
    "transfer.idempotency_records, TRANSFER"
  })
  void concurrentClaimsWithTheSameKeyLetExactlyOneSucceed(String table, String operation)
      throws Exception {
    Map<String, Object> claim = idempotencyRecord(UUID.randomUUID(), operation, UUID.randomUUID());
    int competitors = 2;
    CyclicBarrier start = new CyclicBarrier(competitors);
    ExecutorService executor = Executors.newFixedThreadPool(competitors);
    List<Future<String>> outcomes = new ArrayList<>();
    try {
      for (int i = 0; i < competitors; i++) {
        outcomes.add(
            executor.submit(
                () -> {
                  try (Connection connection = dataSource.getConnection()) {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    insert(connection, table, claim);
                    return "OK";
                  } catch (SQLException e) {
                    return e.getSQLState();
                  }
                }));
      }
      List<String> results = new ArrayList<>();
      for (Future<String> outcome : outcomes) {
        results.add(outcome.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
      }

      assertThat(results).containsExactlyInAnyOrder("OK", UNIQUE_VIOLATION);
    } finally {
      executor.shutdownNow();
    }

    assertThat(countClaims(table, claim)).isEqualTo(1);
  }

  @Test
  void sameKeyForAnotherCustomerOrOperationIsAnotherClaim() throws SQLException {
    UUID customer = UUID.randomUUID();
    UUID key = UUID.randomUUID();

    insert("wallet.idempotency_records", idempotencyRecord(customer, "DEPOSIT", key));
    insert("wallet.idempotency_records", idempotencyRecord(customer, "WITHDRAWAL", key));
    insert("wallet.idempotency_records", idempotencyRecord(UUID.randomUUID(), "DEPOSIT", key));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM wallet.idempotency_records WHERE idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(3);
  }

  private int countClaims(String table, Map<String, Object> claim) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM "
            + table
            + " WHERE customer_id = ? AND operation = ? AND idempotency_key = ?",
        Integer.class,
        claim.get("customer_id"),
        claim.get("operation"),
        claim.get("idempotency_key"));
  }
}
