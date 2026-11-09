package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.idempotency.StoredResponse;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.risk.application.RiskAssessmentRequest;
import com.portfolio.wallet.risk.application.RiskAssessmentService;
import com.portfolio.wallet.transfer.application.TransferIdempotency;
import com.portfolio.wallet.transfer.application.TransferIdempotentRequest;
import com.portfolio.wallet.transfer.domain.TransferRequestFingerprint;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * Lock and statement timeouts end contention within the configured bound (documentations/06
 * MVP-ADR-06 "Lock and statement timeouts"; doc 07 D14 and its open risk Z1; doc 08 §4C.2 Q6, §4C.4
 * criteria 9 and 10, §5 notes of steps 2 and 3).
 *
 * <p>Its own context, with a 200 ms lock timeout and a 1 s statement timeout, so each wait ends
 * quickly. Every refused request is {@code 500 SYSTEM_ERROR} with the generic message, leaves no
 * idempotency record, and the same key then runs fresh.
 */
@TestPropertySource(
    properties = {"app.database.lock-timeout=200ms", "app.database.statement-timeout=1s"})
class LockContentionIT extends MoneyFlowSupport {

  private static final Duration LOCK_TIMEOUT = Duration.ofMillis(200);

  /** Generous: CI machines are slow, but an unbounded wait would never end. */
  private static final Duration BOUND = Duration.ofSeconds(5);

  private static final String GENERIC = "An unexpected error occurred";

  @Autowired TransactionTimeouts timeouts;
  @Autowired TransferIdempotency transferIdempotency;
  @Autowired RiskAssessmentService riskAssessments;
  @Autowired com.portfolio.wallet.wallet.application.WalletStatusService walletStatus;

  private record Timed<T>(T value, Duration elapsed) {}

  private interface Call<T> {
    T run() throws Exception;
  }

  private static <T> Timed<T> timed(Call<T> call) throws Exception {
    long start = System.nanoTime();
    T value = call.run();
    return new Timed<>(value, Duration.ofNanos(System.nanoTime() - start));
  }

  private void assertTimedOut(Timed<MockHttpServletResponse> response) throws Exception {
    assertThat(response.value().getStatus()).isEqualTo(500);
    assertThat(errorCode(response.value())).isEqualTo("SYSTEM_ERROR");
    assertThat(body(response.value()).get("message").asString()).isEqualTo(GENERIC);
    assertThat(response.elapsed()).isGreaterThanOrEqualTo(LOCK_TIMEOUT).isLessThan(BOUND);
  }

  @Test
  void theContextUsesTheConfiguredValues() {
    assertThat(timeouts.lockTimeout()).isEqualTo("200ms");
    assertThat(timeouts.statementTimeout()).isEqualTo("1000ms");
  }

  /** A held wallet lock ends a deposit within the lock timeout; the key then runs fresh. */
  @Test
  void aHeldWalletLockEndsTheMoneyTransactionAndNothingIsStored() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000.00");
    UUID key = UUID.randomUUID();

    try (Connection holder = openTransaction()) {
      queryOne(holder, "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE", UUID.class, wallet);

      assertTimedOut(timed(() -> withdraw(customer, "100.00", key)));
      assertTimedOut(timed(() -> deposit(customer, "100.00", UUID.randomUUID())));

      holder.rollback();
    }
    assertThat(idempotencyRecords(customer.id(), key)).isZero();
    assertThat(rows("wallet.withdrawals", wallet)).isZero();
    assertThat(available(wallet)).isEqualByComparingTo("1000.00");

    MockHttpServletResponse fresh = withdraw(customer, "100.00", key);
    assertThat(fresh.getStatus()).isEqualTo(201);
    assertThat(withdraw(customer, "100.00", key).getContentAsString())
        .isEqualTo(fresh.getContentAsString());
    assertThat(available(wallet)).isEqualByComparingTo("900.00");
    assertReconciled(wallet);
  }

  /** Freeze takes the same wallet lock; it is bounded too (owner, 2026-11-09). */
  @Test
  void aFreezeWaitingOnTheWalletLockEndsWithinTheLockTimeout() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);

    try (Connection holder = openTransaction()) {
      queryOne(holder, "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE", UUID.class, wallet);

      Timed<Throwable> refused =
          timed(
              () -> {
                try {
                  walletStatus.freezeOwn(customer.id(), null);
                  return null;
                } catch (RuntimeException e) {
                  return e;
                }
              });
      assertThat(refused.value()).isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(refused.elapsed()).isGreaterThanOrEqualTo(LOCK_TIMEOUT).isLessThan(BOUND);

      holder.rollback();
    }
    try (Connection reader = openTransaction()) {
      assertThat(
              queryOne(
                  reader, "SELECT status FROM wallet.wallets WHERE id = ?", String.class, wallet))
          .isEqualTo("ACTIVE");
      reader.rollback();
    }
    walletStatus.freezeOwn(customer.id(), null);
    try (Connection reader = openTransaction()) {
      assertThat(
              queryOne(
                  reader, "SELECT status FROM wallet.wallets WHERE id = ?", String.class, wallet))
          .isEqualTo("FROZEN");
      reader.rollback();
    }
  }

  /**
   * A duplicate waiting for an in-flight claim of its key ends within the lock timeout while
   * claiming the row (doc 08 §5, step 2 notes): nothing is stored, and the key runs fresh after.
   */
  @Test
  void aHeldIdempotencyRowEndsTheDuplicateWhileClaimingAndStoresNothing() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();

    try (Connection holder = openTransaction()) {
      // An uncommitted claim of the key, as a first request in flight leaves it.
      insert(
          holder,
          "wallet.idempotency_records",
          row(
              "customer_id", customer.id(),
              "operation", "DEPOSIT",
              "idempotency_key", key,
              "request_hash", "0".repeat(64),
              "expires_at", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));

      assertTimedOut(timed(() -> deposit(customer, "500.00", key)));

      holder.rollback();
    }
    assertThat(idempotencyRecords(customer.id(), key)).isZero();
    assertThat(rows("wallet.deposits", wallet)).isZero();

    assertThat(deposit(customer, "500.00", key).getStatus()).isEqualTo(201);
    assertThat(idempotencyRecords(customer.id(), key)).isOne();
    assertThat(available(wallet)).isEqualByComparingTo("500.00");
  }

  /** The transfer engine has the same bound (Q6): a held claim ends the duplicate, unstored. */
  @Test
  void theTransferEngineIsBoundedTooAndStoresNothing() throws Exception {
    UUID sender = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    TransferIdempotentRequest request =
        new TransferIdempotentRequest(
            key,
            TransferRequestFingerprint.builder(sender, "12345678903", Money.of("10.00", Money.THB))
                .build());
    AtomicInteger runs = new AtomicInteger();

    try (Connection holder = openTransaction()) {
      insert(
          holder,
          "transfer.idempotency_records",
          row(
              "customer_id",
              sender,
              "operation",
              "TRANSFER",
              "idempotency_key",
              key,
              "request_hash",
              "0".repeat(64),
              "expires_at",
              OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));

      Timed<Throwable> refused =
          timed(
              () -> {
                try {
                  transferIdempotency.execute(
                      request,
                      () -> {
                        runs.incrementAndGet();
                        return new StoredResponse(201, "{}");
                      });
                  return null;
                } catch (RuntimeException e) {
                  return e;
                }
              });
      assertThat(refused.value())
          .isInstanceOfSatisfying(
              DataAccessException.class,
              e ->
                  assertThat(((java.sql.SQLException) e.getMostSpecificCause()).getSQLState())
                      .isEqualTo("55P03"));
      assertThat(refused.elapsed()).isGreaterThanOrEqualTo(LOCK_TIMEOUT).isLessThan(BOUND);
      holder.rollback();
    }
    assertThat(runs).hasValue(0);
    assertThat(transferRecords(sender, key)).isZero();

    assertThat(transferIdempotency.execute(request, () -> new StoredResponse(201, "{}")).replayed())
        .isFalse();
    assertThat(transferRecords(sender, key)).isOne();
  }

  /** Step 3 notes: the sender-lock wait of the risk assessment is bounded; nothing is stored. */
  @Test
  void theRiskSenderLockEndsTheAssessmentWithinTheLockTimeout() throws Exception {
    UUID sender = UUID.randomUUID();
    jdbc.update("INSERT INTO risk.sender_risk_state (customer_id) VALUES (?)", sender);
    RiskAssessmentRequest request =
        new RiskAssessmentRequest(
            UUID.randomUUID(),
            sender,
            Money.of("100.00", Money.THB),
            Instant.now().minus(Duration.ofDays(30)),
            true);

    try (Connection holder = openTransaction()) {
      queryOne(
          holder,
          "SELECT customer_id FROM risk.sender_risk_state WHERE customer_id = ? FOR UPDATE",
          UUID.class,
          sender);

      Timed<Throwable> refused =
          timed(
              () -> {
                try {
                  riskAssessments.assess(request);
                  return null;
                } catch (RuntimeException e) {
                  return e;
                }
              });
      assertThat(refused.value())
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
      assertThat(refused.elapsed()).isGreaterThanOrEqualTo(LOCK_TIMEOUT).isLessThan(BOUND);
      holder.rollback();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM risk.risk_assessments WHERE customer_id = ?",
                Integer.class,
                sender))
        .isZero();
    assertThat(riskAssessments.assess(request).assessmentId()).isNotNull();
  }

  /**
   * Doc 07 D14 open risk Z1: the lookup's per-user advisory lock wait is bounded too; the attempt
   * is neither counted nor audited, because its transaction rolled back.
   */
  @Test
  void aLookupWaitingOnItsAdvisoryLockEndsWithinTheLockTimeout() throws Exception {
    Account caller = withWallet();
    String number = (String) walletOf(withWallet().id()).get("wallet_number");

    try (Connection holder = openTransaction()) {
      queryOne(
          holder,
          "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))",
          Integer.class,
          "wallet.lookup.user:" + caller.id());

      assertTimedOut(timed(() -> send(lookupFromOwnAddress(number), caller.token())));
      holder.rollback();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM wallet.lookup_attempts WHERE user_id = ?",
                Integer.class,
                caller.id()))
        .isZero();
    assertThat(send(lookupFromOwnAddress(number), caller.token()).getStatus()).isEqualTo(200);
  }

  /** A slow statement inside the money transaction ends at the statement timeout, unstored. */
  @Test
  void aSlowStatementEndsWithinTheStatementTimeoutAndNothingIsStored() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();
    // Runs on the money transaction's connection, after the idempotency row is claimed.
    doAnswer(
            invocation -> {
              jdbc.execute("SELECT pg_sleep(3)");
              return invocation.callRealMethod();
            })
        .when(movementRowsSpy)
        .findByCustomerAndKey(any(), any(), any());

    Timed<MockHttpServletResponse> response = timed(() -> deposit(customer, "100.00", key));

    assertThat(response.value().getStatus()).isEqualTo(500);
    assertThat(errorCode(response.value())).isEqualTo("SYSTEM_ERROR");
    assertThat(body(response.value()).get("message").asString()).isEqualTo(GENERIC);
    assertThat(response.elapsed()).isGreaterThanOrEqualTo(Duration.ofSeconds(1)).isLessThan(BOUND);
    assertThat(idempotencyRecords(customer.id(), key)).isZero();

    Mockito.reset(movementRowsSpy);
    assertThat(deposit(customer, "100.00", key).getStatus()).isEqualTo(201);
    assertThat(available(wallet)).isEqualByComparingTo("100.00");
  }

  /** The timeouts are set in the money transaction, and nowhere else on the pool afterwards. */
  @Test
  void noConnectionOfThePoolKeepsTheTimeoutsAfterTheMoneyTransactions() throws Exception {
    Account customer = withWallet();
    for (int i = 0; i < 5; i++) {
      funded(customer, "100.00");
    }
    // Every pooled connection, taken at once, shows PostgreSQL's default: no timeout.
    int connections =
        dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize();
    Connection[] taken = new Connection[connections];
    try {
      for (int i = 0; i < connections; i++) {
        taken[i] = dataSource.getConnection();
        assertThat(queryOne(taken[i], "SHOW lock_timeout", String.class)).isEqualTo("0");
        assertThat(queryOne(taken[i], "SHOW statement_timeout", String.class)).isEqualTo("0");
      }
    } finally {
      for (Connection connection : taken) {
        if (connection != null) {
          connection.close();
        }
      }
    }
  }

  /**
   * Its own client address, so lookups of other tests in the run do not share the per-IP window.
   */
  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      lookupFromOwnAddress(String number) {
    java.util.concurrent.ThreadLocalRandom random =
        java.util.concurrent.ThreadLocalRandom.current();
    String ip =
        "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    return get("/api/v1/wallets/lookup")
        .param("walletNumber", number)
        .with(
            r -> {
              r.setRemoteAddr(ip);
              return r;
            });
  }

  private int transferRecords(UUID customer, UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM transfer.idempotency_records"
            + " WHERE customer_id = ? AND idempotency_key = ?",
        Integer.class,
        customer,
        key);
  }
}
