package com.portfolio.wallet.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TransactionTimeouts} against PostgreSQL (documentations/06 MVP-ADR-06 "Lock and statement
 * timeouts"; doc 07 D14; doc 08 §4C.2 Q6): the values hold for the transaction, bound a lock wait
 * and a slow statement, and end with the transaction on commit, on rollback and after a timeout, so
 * the next transaction on the same pooled connection is not affected.
 *
 * <p>The "same connection" is guaranteed, not hoped for: the bounded instance runs on one pooled
 * {@code wallet_app} connection through a {@link SingleConnectionDataSource}.
 */
class TransactionTimeoutsIT extends DatabaseIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  @Autowired TransactionTimeouts contextTimeouts;
  @Autowired PlatformTransactionManager transactionManager;

  private Connection pooled;
  private JdbcClient single;
  private TransactionTemplate singleTransactions;
  private TransactionTimeouts bounded;

  @BeforeEach
  void oneConnection() throws Exception {
    pooled = dataSource.getConnection();
    SingleConnectionDataSource one = new SingleConnectionDataSource(pooled, true);
    single = JdbcClient.create(one);
    singleTransactions = new TransactionTemplate(new DataSourceTransactionManager(one));
    bounded =
        new TransactionTimeouts(
            single, new DatabaseTimeoutProperties(Duration.ofMillis(200), Duration.ofMillis(300)));
  }

  @AfterEach
  void release() throws Exception {
    // Back to the pool: it must be as clean as it came.
    assertThat(show("lock_timeout")).isEqualTo("0");
    assertThat(show("statement_timeout")).isEqualTo("0");
    pooled.close();
  }

  private String show(String setting) {
    return single.sql("SHOW " + setting).query(String.class).single();
  }

  /** The settings a new transaction on the same connection starts with. */
  private Map<String, Object> nextTransactionSettings() {
    Map<String, Object> next = singleTransactions.execute(status -> settings());
    return next;
  }

  private Map<String, Object> settings() {
    return single
        .sql(
            "SELECT current_setting('lock_timeout') AS lock,"
                + " current_setting('statement_timeout') AS statement,"
                + " pg_backend_pid() AS pid")
        .query()
        .singleRow();
  }

  @Test
  void theContextBeanSetsTheMvpDefaultsForItsTransactionOnly() {
    Map<String, Object> inside =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  contextTimeouts.apply();
                  return jdbc.queryForMap(
                      "SELECT current_setting('lock_timeout') AS lock,"
                          + " current_setting('statement_timeout') AS statement");
                });

    assertThat(inside).containsEntry("lock", "5s").containsEntry("statement", "10s");
  }

  @Test
  void theSettingsHoldInTheTransactionAndEndWithItsCommit() {
    Map<String, Object> inside =
        singleTransactions.execute(
            status -> {
              bounded.apply();
              return settings();
            });
    Map<String, Object> next = singleTransactions.execute(status -> settings());

    assertThat(inside).containsEntry("lock", "200ms").containsEntry("statement", "300ms");
    assertThat(next.get("pid")).isEqualTo(inside.get("pid"));
    assertThat(next).containsEntry("lock", "0").containsEntry("statement", "0");
  }

  @Test
  void theSettingsEndWithARollback() {
    assertThatThrownBy(
            () ->
                singleTransactions.execute(
                    status -> {
                      bounded.apply();
                      throw new IllegalStateException("rolled back");
                    }))
        .hasMessage("rolled back");

    assertThat(nextTransactionSettings())
        .containsEntry("lock", "0")
        .containsEntry("statement", "0");
  }

  @Test
  void aLockWaitEndsAtTheLockTimeout() throws Exception {
    UUID wallet = newWallet();
    try (Connection holder = openTransaction()) {
      queryOne(holder, "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE", UUID.class, wallet);
      long start = System.nanoTime();

      assertThatThrownBy(
              () ->
                  singleTransactions.execute(
                      status -> {
                        bounded.apply();
                        return single
                            .sql("SELECT id FROM wallet.wallets WHERE id = :id FOR UPDATE")
                            .param("id", wallet)
                            .query(UUID.class)
                            .single();
                      }))
          .isInstanceOfSatisfying(
              DataAccessException.class, e -> assertThat(lockTimeout(e)).isTrue());

      Duration waited = Duration.ofNanos(System.nanoTime() - start);
      assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(200)).isLessThan(BOUND);
      holder.rollback();
    }
    // The failed transaction's settings ended with its rollback.
    assertThat(nextTransactionSettings()).containsEntry("lock", "0");
  }

  @Test
  void aSlowStatementEndsAtTheStatementTimeout() {
    long start = System.nanoTime();

    assertThatThrownBy(
            () ->
                singleTransactions.executeWithoutResult(
                    status -> {
                      bounded.apply();
                      single.sql("SELECT pg_sleep(3)").query().listOfRows();
                    }))
        .isInstanceOf(QueryTimeoutException.class);

    Duration waited = Duration.ofNanos(System.nanoTime() - start);
    assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(300)).isLessThan(BOUND);
    assertThat(nextTransactionSettings()).containsEntry("statement", "0");
  }

  /** PostgreSQL's lock_not_available, whatever Spring names the exception. */
  private static boolean lockTimeout(DataAccessException e) {
    return e.getMostSpecificCause() instanceof java.sql.SQLException sql
        && "55P03".equals(sql.getSQLState());
  }

  /** Review L1: the mark is set by apply() and ends with its transaction, commit or rollback. */
  @Test
  void theAppliedMarkHoldsForItsTransactionOnly() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    TransactionTemplate inner = new TransactionTemplate(transactionManager);
    inner.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    Boolean before = template.execute(status -> contextTimeouts.applied());
    Boolean inside =
        template.execute(
            status -> {
              contextTimeouts.apply();
              return contextTimeouts.applied();
            });
    Boolean afterCommit = template.execute(status -> contextTimeouts.applied());
    assertThatThrownBy(
            () ->
                template.executeWithoutResult(
                    status -> {
                      contextTimeouts.apply();
                      throw new IllegalStateException("rolled back");
                    }))
        .hasMessage("rolled back");
    Boolean afterRollback = template.execute(status -> contextTimeouts.applied());
    // A transaction of its own inside does not inherit the outer one's mark.
    Boolean nested =
        template.execute(
            status -> {
              contextTimeouts.apply();
              return inner.execute(innerStatus -> contextTimeouts.applied());
            });

    assertThat(before).isFalse();
    assertThat(inside).isTrue();
    assertThat(afterCommit).isFalse();
    assertThat(afterRollback).isFalse();
    assertThat(nested).isFalse();
    assertThat(contextTimeouts.applied()).isFalse();
  }

  @Test
  void outsideATransactionItRefuses() {
    assertThatThrownBy(bounded::apply).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(contextTimeouts::apply).isInstanceOf(IllegalStateException.class);
  }
}
