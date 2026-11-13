package com.portfolio.wallet.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link TransactionTimeouts} sets both D14 timeouts in one transaction-local statement and refuses
 * to run outside a transaction (documentations/07 D14; doc 08 §4C.2 Q6). That the settings bound
 * the waits and end with the transaction is proved against PostgreSQL in {@code
 * TransactionTimeoutsIT}.
 */
class TransactionTimeoutsTest {

  private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
  private final TransactionTimeouts timeouts =
      new TransactionTimeouts(
          jdbc, new DatabaseTimeoutProperties(Duration.ofSeconds(5), Duration.ofMillis(10_250)));

  @AfterEach
  void noTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /** What a transaction manager does when it begins a transaction. */
  private static void beginTransaction() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @Test
  void theValuesAreInMilliseconds() {
    assertThat(timeouts.lockTimeout()).isEqualTo("5000ms");
    assertThat(timeouts.statementTimeout()).isEqualTo("10250ms");
  }

  @Test
  void bothAreSetLocallyInOneStatementWithBoundValues() {
    beginTransaction();

    timeouts.apply();

    assertThat(TransactionTimeouts.SQL)
        .isEqualTo(
            "SELECT set_config('lock_timeout', :lock, true),"
                + " set_config('statement_timeout', :statement, true)");
    verify(jdbc).sql(TransactionTimeouts.SQL);
    verify(jdbc.sql(TransactionTimeouts.SQL)).param("lock", "5000ms");
    verify(jdbc.sql(TransactionTimeouts.SQL).param("lock", "5000ms")).param("statement", "10250ms");
  }

  @Test
  void outsideATransactionItRefusesAndRunsNothing() {
    assertThatThrownBy(timeouts::apply)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inside a transaction");
    verifyNoInteractions(jdbc);
  }

  /** Review L1: the mark lets lock() enforce D14; it is registered once per transaction. */
  @Test
  void applyingMarksTheTransactionOnce() {
    assertThat(timeouts.applied()).isFalse();
    beginTransaction();
    assertThat(timeouts.applied()).isFalse();

    timeouts.apply();
    timeouts.apply();

    assertThat(timeouts.applied()).isTrue();
    assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
  }

  /** The mark belongs to the transaction's synchronizations, which end with it. */
  @Test
  void theMarkDoesNotSurviveTheTransaction() {
    beginTransaction();
    timeouts.apply();
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);

    assertThat(timeouts.applied()).isFalse();
    beginTransaction();
    assertThat(timeouts.applied()).isFalse();
  }

  @Test
  void withoutSynchronizationItRefusesToo() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(timeouts::apply).isInstanceOf(IllegalStateException.class);
    assertThat(timeouts.applied()).isFalse();
    verifyNoInteractions(jdbc);
  }
}
