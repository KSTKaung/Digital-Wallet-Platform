package com.portfolio.wallet.notification.application;

import com.portfolio.wallet.common.time.BusinessTimeProperties;
import com.portfolio.wallet.notification.persistence.NotificationRepository;
import com.portfolio.wallet.notification.persistence.OutboxRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the email outbox (documentations/06 MVP-ADR-25), after the business transaction that wrote
 * a row has committed, so a failed lookup or delivery only updates the outbox row and never undoes
 * the change. Delivery is <strong>at least once</strong>, never exactly once; the state transitions
 * are idempotent.
 *
 * <p>Each row is handled in three steps (owner, 2026-11-04, N1):
 *
 * <ol>
 *   <li><strong>Claim</strong>, in a short transaction: the oldest due {@code PENDING} row with
 *       fewer than {@link OutboxProperties#maxAttempts()} claims is locked ({@code FOR UPDATE SKIP
 *       LOCKED}), its attempt count goes up by one and its next attempt time is moved forward by
 *       the {@linkplain OutboxProperties#lease() lease}, and the transaction commits. The new
 *       attempt count is the claim's <strong>fencing token</strong> (R1). Until the lease runs out
 *       no other run claims the row; there is never a claim beyond the maximum.
 *   <li><strong>Send</strong>, with no transaction open: resolve the kind, look up the address
 *       through {@link RecipientAddresses} and hand the message to {@link EmailSender}. Any failure
 *       here (an unknown kind, a missing address, a database error in the lookup, an SMTP error)
 *       fails this attempt.
 *   <li><strong>Record</strong>, in a short transaction: {@code SENT}; or, after a failure, the
 *       next attempt time by the backoff, or {@code FAILED} if this was the last allowed claim. The
 *       update is conditional on {@code PENDING} and on the fencing token, so the result of an
 *       older claim, whose lease ran out and whose row was claimed again, is ignored.
 * </ol>
 *
 * <p>The last allowed claim stays {@code PENDING} while it sends: it becomes {@code SENT} if the
 * send succeeds and {@code FAILED} if it fails. If its lease ends without a result (a crash, or a
 * result that could not be recorded), the start of the next run marks it {@code FAILED} with {@code
 * LeaseExpired}. So a row that keeps crashing ends {@code FAILED} after the maximum number of
 * claims instead of being claimed forever.
 *
 * <p>A stop of the process, or a failure to record the result, after the SMTP server accepted a
 * message leaves the row {@code PENDING}; it is claimed again when the lease ends (if claims
 * remain) and that one email is sent again. This duplicate is the accepted limit of SMTP
 * (MVP-ADR-25). A failure on one row never stops the run: the next row is handled, up to {@link
 * OutboxProperties#batchSize()}.
 *
 * <p>An unknown kind (a stored name this build does not know, for example after a rollback of the
 * code) fails the attempt like any other failure, so the row is retried on the schedule and ends
 * {@code FAILED} if the kind stays unknown; a redeploy that knows it again still delivers it.
 *
 * <p>Logs name the event id, the kind and the failure's class only: never the address, which mail
 * exceptions quote, and never the message text.
 */
@Service
public class OutboxDispatcher {

  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

  /** The recorded failure when the account has no address (or no longer exists). */
  static final String NO_ADDRESS = "NoAddress";

  /** The recorded failure for a stored kind this build does not know. */
  static final String UNKNOWN_KIND = "UnknownKind";

  /** The size of {@code notification.notifications.last_failure} (V9). */
  static final int MAX_FAILURE_LENGTH = 100;

  private final NotificationRepository notifications;
  private final RecipientAddresses recipients;
  private final EmailSender sender;
  private final OutboxProperties properties;
  private final BusinessTimeProperties businessTime;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public OutboxDispatcher(
      NotificationRepository notifications,
      RecipientAddresses recipients,
      EmailSender sender,
      OutboxProperties properties,
      BusinessTimeProperties businessTime,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.notifications = notifications;
    this.recipients = recipients;
    this.sender = sender;
    this.properties = properties;
    this.businessTime = businessTime;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * One run: first marks {@code FAILED} the rows whose last claim ran out without a result, then
   * handles due rows, oldest due first, at most {@link OutboxProperties#batchSize()} of them. A
   * failure on one row is recorded or left to its lease, and the run goes on; only a failing
   * database statement of the run itself, which means the database is unavailable, ends it early.
   *
   * @return how many rows were claimed (sent, retried later, failed for good, or left to the lease)
   */
  public int dispatchDue() {
    try {
      failExpiredLastClaims();
    } catch (RuntimeException e) {
      log.warn("Email outbox run stopped: failure={}", e.getClass().getSimpleName());
      return 0;
    }
    int handled = 0;
    while (handled < properties.batchSize()) {
      Optional<OutboxRow> row;
      try {
        row =
            claim(
                (now, leaseUntil) ->
                    notifications.claimNextDue(now, leaseUntil, properties.maxAttempts()));
      } catch (RuntimeException e) {
        log.warn("Email outbox run stopped: failure={}", e.getClass().getSimpleName());
        break;
      }
      if (row.isEmpty()) {
        break;
      }
      handled++;
      handle(row.get());
    }
    return handled;
  }

  /**
   * Handles one row if it is {@code PENDING}, due, not leased by another run, and has claims left.
   *
   * @return whether the row was claimed; false for a row that is {@code SENT}, {@code FAILED}, not
   *     yet due, leased, out of claims, or unknown
   */
  public boolean dispatch(UUID eventId) {
    failExpiredLastClaims();
    Optional<OutboxRow> row =
        claim(
            (now, leaseUntil) ->
                notifications.claimDue(eventId, now, leaseUntil, properties.maxAttempts()));
    row.ifPresent(this::handle);
    return row.isPresent();
  }

  // The last allowed claim whose lease ended without a result: FAILED, never a further claim.
  private void failExpiredLastClaims() {
    List<OutboxRow> expired =
        transactions.execute(
            status ->
                notifications.failExpiredLastClaims(clock.instant(), properties.maxAttempts()));
    for (OutboxRow row : expired) {
      log.warn(
          "Email outbox: last attempt's lease ended without a result, failed for good:"
              + " eventId={}, kind={}",
          row.eventId(),
          row.kind());
    }
  }

  // Step 1: a short transaction that counts the attempt, leases the row and commits.
  private Optional<OutboxRow> claim(
      BiFunction<Instant, Instant, Optional<OutboxRow>> claimStatement) {
    Instant now = clock.instant();
    return transactions.execute(status -> claimStatement.apply(now, now.plus(properties.lease())));
  }

  // Steps 2 and 3. Never throws: a row whose result cannot be recorded waits for its lease.
  private void handle(OutboxRow row) {
    String failure = deliver(row);
    try {
      transactions.executeWithoutResult(status -> record(row, failure));
    } catch (RuntimeException e) {
      log.warn(
          "Email outbox: result not recorded, the row is claimed again after its lease:"
              + " eventId={}, kind={}, failure={}",
          row.eventId(),
          row.kind(),
          e.getClass().getSimpleName());
    }
  }

  /**
   * Step 2, with no transaction open.
   *
   * @return null once the SMTP server accepted the message, otherwise the failure's name
   */
  private String deliver(OutboxRow row) {
    EmailKind kind;
    try {
      kind = EmailKind.valueOf(row.kind());
    } catch (IllegalArgumentException e) {
      return UNKNOWN_KIND;
    }
    try {
      Optional<String> address =
          recipients.emailOf(row.userId(), properties.addressLookupTimeout());
      if (address.isEmpty()) {
        return NO_ADDRESS;
      }
      sender.send(
          OutboxTemplates.render(
              kind, address.get(), row.occurredAt(), businessTime.businessZone()));
      return null;
    } catch (RuntimeException e) {
      return e.getClass().getSimpleName();
    }
  }

  // Step 3. Fenced by the claim's attempt count; 0 rows means a newer claim took the row over.
  private void record(OutboxRow row, String failure) {
    int claim = row.attempts();
    if (failure == null) {
      if (notifications.markSent(row.eventId(), claim) == 1) {
        log.info("Email outbox: sent eventId={}, kind={}", row.eventId(), row.kind());
      } else {
        logOlderClaim(row);
      }
      return;
    }
    String recorded = bounded(failure);
    if (claim >= properties.maxAttempts()) {
      if (notifications.markFailed(row.eventId(), claim, recorded) == 1) {
        log.warn(
            "Email outbox: delivery failed for good: eventId={}, kind={}, attempts={}, failure={}",
            row.eventId(),
            row.kind(),
            claim,
            recorded);
      } else {
        logOlderClaim(row);
      }
      return;
    }
    Instant next = clock.instant().plus(properties.waitAfter(claim));
    if (notifications.scheduleRetry(row.eventId(), claim, next, recorded) == 1) {
      log.info(
          "Email outbox: delivery failed, will retry: eventId={}, kind={}, attempts={}, failure={}",
          row.eventId(),
          row.kind(),
          claim,
          recorded);
    } else {
      logOlderClaim(row);
    }
  }

  private static void logOlderClaim(OutboxRow row) {
    log.info(
        "Email outbox: result from an older claim ignored: eventId={}, kind={}, attempt={}",
        row.eventId(),
        row.kind(),
        row.attempts());
  }

  static String bounded(String failure) {
    return failure.length() > MAX_FAILURE_LENGTH
        ? failure.substring(0, MAX_FAILURE_LENGTH)
        : failure;
  }
}
