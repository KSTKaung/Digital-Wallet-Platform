package com.portfolio.wallet.common.database;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounds every lock wait and statement of the current transaction (documentations/06 MVP-ADR-06
 * "Lock and statement timeouts"; doc 07 D14; doc 08 §4C.2 Q6).
 *
 * <p>{@link #apply()} sets PostgreSQL's {@code lock_timeout} and {@code statement_timeout} for the
 * current transaction only, with {@code set_config(name, value, true)}: the function form of {@code
 * SET LOCAL}, with the same effect (the setting ends with the transaction, on commit and on
 * rollback, so it never leaks to the next user of the pooled connection) and with bound parameters,
 * both in one statement. Callers run it as the <b>first statement</b> of the transaction, before
 * its first lock, through the "transaction preparer" of the idempotency engine and the risk
 * assessment.
 *
 * <p>It refuses to run outside a transaction: there {@code SET LOCAL} would end with the statement
 * itself and bound nothing, so the caller would believe it is protected while it is not.
 *
 * <p>A timeout makes PostgreSQL cancel the statement ({@code 55P03} for a lock, {@code 57014} for a
 * statement). Both reach the caller as a {@code DataAccessException} (today an {@code
 * UncategorizedSQLException} with SQL state {@code 55P03}, and a {@code QueryTimeoutException}),
 * which rolls the transaction back and reaches the caller as {@code 500 SYSTEM_ERROR} with the
 * generic message. It is not one of the stored business refusals, so no idempotency record keeps it
 * (MVP-ADR-06).
 */
@Component
public class TransactionTimeouts {

  static final String SQL =
      "SELECT set_config('lock_timeout', :lock, true),"
          + " set_config('statement_timeout', :statement, true)";

  private final JdbcClient jdbc;
  private final String lockTimeout;
  private final String statementTimeout;

  public TransactionTimeouts(JdbcClient jdbc, DatabaseTimeoutProperties properties) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    Objects.requireNonNull(properties, "properties");
    this.lockTimeout = setting(properties.lockTimeout().toMillis());
    this.statementTimeout = setting(properties.statementTimeout().toMillis());
  }

  /** PostgreSQL's notation for a duration in milliseconds. */
  static String setting(long millis) {
    return millis + "ms";
  }

  /**
   * Sets both timeouts for the rest of the current transaction.
   *
   * @throws IllegalStateException if no transaction is active (a programming error)
   */
  public void apply() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "transaction timeouts are set inside a transaction; outside one they bound nothing");
    }
    jdbc.sql(SQL)
        .param("lock", lockTimeout)
        .param("statement", statementTimeout)
        .query()
        .listOfRows();
    if (!applied()) {
      TransactionSynchronizationManager.registerSynchronization(new Applied());
    }
  }

  /**
   * Whether {@link #apply()} ran in the current transaction, so callers that take money locks can
   * enforce D14 instead of trusting it ({@code WalletTransferService#lock}).
   *
   * <p>The mark is a transaction synchronization: Spring keeps one list per transaction, suspends
   * it with its transaction (a {@code REQUIRES_NEW} inside does not see it) and clears it at
   * completion, so it never survives into the next transaction on the thread or connection.
   */
  public boolean applied() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.getSynchronizations().stream()
            .anyMatch(Applied.class::isInstance);
  }

  /** The mark of {@link #applied()}; it does nothing at completion. */
  private static final class Applied implements TransactionSynchronization {}

  /** The {@code lock_timeout} value {@link #apply()} sets, such as {@code 5000ms}. */
  public String lockTimeout() {
    return lockTimeout;
  }

  /** The {@code statement_timeout} value {@link #apply()} sets, such as {@code 10000ms}. */
  public String statementTimeout() {
    return statementTimeout;
  }
}
