package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.transfer.application.HeldTransferDecider.Request;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRepository.DueTransfer;
import com.portfolio.wallet.transfer.persistence.TransferRepository.LockMode;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The expiry job of held transfers (documentations/06 MVP-ADR-24 "Expiry", "Concurrency", "Review
 * outcomes" case 5, "Jobs (Q11)"; doc 08 §4C.2 Q11, §4C.4 criteria 8, 11, 12, 14a; §5 notes).
 *
 * <h2>One run</h2>
 *
 * The candidates are the {@code PENDING_REVIEW} transfers whose {@code review_expires_at} is at or
 * before the database time ({@link TransferRepository#findDueForExpiry}), soonest deadline first.
 * They are read in pages, each starting after the last candidate of the one before, until a page
 * comes back empty or {@link TransferExpiryProperties#maxPerRun()} candidates were expired or
 * skipped; the rest wait for the next run. Each candidate is handled once per run, whatever its
 * outcome. A failed candidate does not count toward the maximum, so transfers that fail on every
 * run cannot use it up and keep later due transfers from expiring.
 *
 * <h2>One transfer, one transaction</h2>
 *
 * <ol>
 *   <li>the D14 timeouts ({@link TransactionTimeouts#apply()}), first;
 *   <li>the transfer row {@code FOR UPDATE SKIP LOCKED} in a statement of its own: a row another
 *       transaction holds (a cancel, a review, another instance's run) is skipped, never waited on,
 *       and is handled by a later run if it is still held then;
 *   <li>under that lock the status must still be {@code PENDING_REVIEW}; otherwise it is skipped
 *       silently: no review row, no audit row, no email;
 *   <li>{@link HeldTransferDecider} with {@link Request#EXPIRY_JOB}: both wallets locked, the hold
 *       released on the stored {@code limit_date} in any wallet status, {@code EXPIRED} with the
 *       {@code version} check, review case 5 (the system, no actor, {@code
 *       REVIEW_DEADLINE_PASSED}), the {@code TRANSFER_REVIEW} audit row with actor type {@code
 *       SYSTEM}, and the sender's {@code TRANSFER_EXPIRED} email.
 * </ol>
 *
 * A transfer that fails rolls back alone: its row stays held for the next run, and the failure is
 * logged under the alertable key {@code TRANSFER_EXPIRY_FAILED} with the transfer id and the
 * exception's class only (never its message, which can quote SQL or row values); the run goes on.
 * Only a failing candidate query ends a run early.
 *
 * <h2>Several instances</h2>
 *
 * Each instance runs the job on its own. {@code SKIP LOCKED}, the status check under the row lock,
 * the V4 guard (only {@code PENDING_REVIEW} changes, {@code EXPIRED} only at or after the deadline)
 * and {@code uq_risk_reviews_transfer} make each transfer expire once, with one review row, one
 * audit row and one email, and release its hold exactly once (Q4 invariant).
 */
@Service
public class TransferExpiryService {

  private static final Logger log = LoggerFactory.getLogger(TransferExpiryService.class);

  /**
   * The log line of a transfer that could not be expired, starting with a fixed key that an alert
   * can match: a transfer that fails on every run stays held past its deadline until someone acts.
   * The transfer id and the exception's class only.
   */
  static final String FAILURE_LOG =
      "TRANSFER_EXPIRY_FAILED: transfer not expired, retried by the next run:"
          + " transferId={}, failure={}";

  private final TransferRepository transfers;
  private final HeldTransferDecider decider;
  private final TransactionTimeouts timeouts;
  private final TransferExpiryProperties properties;
  private final TransactionTemplate transactions;

  TransferExpiryService(
      TransferRepository transfers,
      HeldTransferDecider decider,
      TransactionTimeouts timeouts,
      TransferExpiryProperties properties,
      PlatformTransactionManager transactionManager) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.decider = Objects.requireNonNull(decider, "decider");
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.transactions = new TransactionTemplate(transactionManager);
    // Each statement sees what the previous holder of the row lock committed.
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * One run, as described on the class. Opens its own transactions and refuses to run inside a
   * caller's: joined to an outer transaction, one failing transfer would roll back the others.
   *
   * @return how many transfers this run expired
   * @throws IllegalTransactionStateException inside a transaction
   */
  public int expireDue() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "the transfer expiry runs in transactions of its own");
    }
    int expired = 0;
    int skipped = 0;
    int failed = 0;
    DueTransfer after = null;
    // Only expired and skipped transfers use the budget: a transfer that fails on every run can
    // never use it up and keep later due transfers waiting. The keyset still bounds the run, which
    // ends on an empty page, and handles each candidate once.
    while (expired + skipped < properties.maxPerRun()) {
      List<DueTransfer> page;
      try {
        page = candidates(after, properties.maxPerRun() - expired - skipped);
      } catch (RuntimeException e) {
        log.warn("Transfer expiry run stopped: failure={}", e.getClass().getSimpleName());
        break;
      }
      if (page.isEmpty()) {
        break;
      }
      for (DueTransfer due : page) {
        after = due;
        try {
          if (expire(due.id())) {
            expired++;
          } else {
            skipped++;
          }
        } catch (RuntimeException e) {
          failed++;
          log.warn(FAILURE_LOG, due.id(), e.getClass().getSimpleName());
        }
      }
    }
    if (expired + skipped + failed > 0) {
      log.info(
          "Transfer expiry run: expired={}, skipped={}, failed={}, limitReached={}",
          expired,
          skipped,
          failed,
          expired + skipped >= properties.maxPerRun());
    }
    return expired;
  }

  private List<DueTransfer> candidates(DueTransfer after, int limit) {
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              timeouts.apply();
              return transfers.findDueForExpiry(after, limit);
            }));
  }

  /**
   * Expires one transfer in a transaction of its own.
   *
   * @return true if it expired; false if another transaction held its row, or it was no longer held
   *     for review under the lock
   */
  private boolean expire(UUID transferId) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              timeouts.apply();
              Optional<TransferRow> locked =
                  transfers.lockForUpdate(transferId, LockMode.SKIP_LOCKED);
              if (locked.isEmpty()
                  || !TransferStatus.PENDING_REVIEW.name().equals(locked.get().status())) {
                return false;
              }
              decider.decide(locked.get(), Request.EXPIRY_JOB, null, null);
              return true;
            }));
  }
}
