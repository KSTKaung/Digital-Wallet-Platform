package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.transfer.persistence.TransferRepository.LockMode;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A lock wait ends within the D14 timeout as {@code 500 SYSTEM_ERROR} (documentations/06 MVP-ADR-06
 * "Lock and statement timeouts"; doc 07 D14; doc 08 §4C.2 Q6, §4C.4 criteria 9, 10, 11): in the
 * transfer's money transaction nothing is stored for the key, so it then runs fresh; in a cancel no
 * review or audit row is written and the hold stays. Its own context, with a 200 ms lock timeout,
 * and the test risk policy of {@link TransferReviewFlowSupport} so a transfer can be held (10.00
 * THB is still {@code ALLOW}).
 */
@TestPropertySource(
    properties = {"app.database.lock-timeout=200ms", "app.database.statement-timeout=1s"})
class TransferLockTimeoutIT extends TransferReviewFlowSupport {

  /** Generous: CI machines are slow, but an unbounded wait would never end. */
  private static final long BOUND_MILLIS = 5_000;

  @Autowired TransactionTimeouts timeouts;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void aLockTimeoutIs500AndIsNotStored() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    MockHttpServletResponse timedOut;
    long started;
    long elapsedMillis;
    try (Connection holder = openTransaction()) {
      // Another transaction holds the sender's wallet row.
      queryOne(
          holder,
          "SELECT 1 FROM wallet.wallets WHERE id = ? FOR UPDATE",
          Integer.class,
          walletId(sender));
      started = System.nanoTime();
      timedOut = transfer(sender, recipient, "10.00", key);
      elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
      holder.rollback();
    }

    assertThat(timedOut.getStatus()).isEqualTo(500);
    assertThat(errorCode(timedOut)).isEqualTo("SYSTEM_ERROR");
    assertThat(body(timedOut).get("message").asString()).isEqualTo("An unexpected error occurred");
    assertThat(elapsedMillis).isLessThan(BOUND_MILLIS);
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(transfersOf(sender)).isEmpty();
    assertBalances(sender, "100.00", "0.00");

    MockHttpServletResponse retried = transfer(sender, recipient, "10.00", key);
    assertThat(retried.getStatus()).isEqualTo(201);
    assertBalances(sender, "90.00", "0.00");
  }

  /**
   * Q6 on the hold path (PR 5a step 7): the money transaction of a {@code MANUAL_REVIEW} transfer
   * waits on the recipient's wallet, ends at the lock timeout with {@code 500}, reserves nothing
   * and stores nothing for the key; the retry with the same key holds once.
   */
  @Test
  void aLockTimeoutOnTheHoldPathIs500ReservesNothingAndIsNotStored() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    MockHttpServletResponse timedOut;
    long elapsedMillis;
    try (Connection holder = openTransaction()) {
      queryOne(
          holder,
          "SELECT 1 FROM wallet.wallets WHERE id = ? FOR UPDATE",
          Integer.class,
          walletId(recipient));
      long started = System.nanoTime();
      timedOut = transfer(sender, recipient, HOLD, key);
      elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
      holder.rollback();
    }

    assertThat(timedOut.getStatus()).isEqualTo(500);
    assertThat(errorCode(timedOut)).isEqualTo("SYSTEM_ERROR");
    assertThat(elapsedMillis).isLessThan(BOUND_MILLIS);
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(transfersOf(sender)).isEmpty();
    assertBalances(sender, "2000.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    assertThat(transferEmails(sender)).isEmpty();

    MockHttpServletResponse retried = transfer(sender, recipient, HOLD, key);
    assertThat(retried.getStatus()).isEqualTo(201);
    assertThat(body(retried).get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("1300.00");
  }

  @Test
  void aCancelBlockedOnTheTransferRowIs500AndLeavesTheHold() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);

    assertCancelTimesOutWhileHeld(
        sender, id, "SELECT 1 FROM transfer.transfers WHERE id = ? FOR UPDATE", id);
  }

  @Test
  void aCancelBlockedOnAWalletIs500AndLeavesTheHold() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);

    assertCancelTimesOutWhileHeld(
        sender, id, "SELECT 1 FROM wallet.wallets WHERE id = ? FOR UPDATE", walletId(recipient));
  }

  private void assertCancelTimesOutWhileHeld(Account sender, UUID id, String lockSql, UUID row)
      throws Exception {
    MockHttpServletResponse timedOut;
    long elapsedMillis;
    try (Connection holder = openTransaction()) {
      queryOne(holder, lockSql, Integer.class, row);
      long started = System.nanoTime();
      timedOut = cancel(id, sender);
      elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
      holder.rollback();
    }

    assertThat(timedOut.getStatus()).isEqualTo(500);
    assertThat(errorCode(timedOut)).isEqualTo("SYSTEM_ERROR");
    assertThat(elapsedMillis).isLessThan(BOUND_MILLIS);
    // Nothing was written: still held, the hold intact, no review or audit row.
    assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("1300.00");
    assertThat(reviewRows(id)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'TRANSFER_REVIEW'"
                    + " AND resource_id = ?",
                Integer.class,
                id.toString()))
        .isZero();

    // Once the lock is gone, the cancel works.
    assertThat(cancel(id, sender).getStatus()).isEqualTo(200);
    assertBalances(sender, "2000.00", "0.00");
  }

  /** The step 6 lock mode: a row another transaction holds is skipped, not waited for. */
  @Test
  void skipLockedSkipsAHeldRowWhileWaitEndsAtTheLockTimeout() throws Exception {
    Account sender = funded("2000.00");
    UUID id = held(sender, withWallet());
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);

    try (Connection holder = openTransaction()) {
      queryOne(
          holder, "SELECT 1 FROM transfer.transfers WHERE id = ? FOR UPDATE", Integer.class, id);

      assertThat(lockInTransaction(transactions, id, LockMode.SKIP_LOCKED)).isEmpty();
      assertThatThrownBy(() -> lockInTransaction(transactions, id, LockMode.WAIT))
          .isInstanceOf(DataAccessException.class);
      holder.rollback();
    }
    assertThat(lockInTransaction(transactions, id, LockMode.SKIP_LOCKED))
        .map(TransferRow::status)
        .contains("PENDING_REVIEW");
  }

  private Optional<TransferRow> lockInTransaction(
      TransactionTemplate transactions, UUID id, LockMode mode) {
    return transactions.execute(
        status -> {
          timeouts.apply();
          return transferRowsSpy.lockForUpdate(id, mode);
        });
  }
}
