package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.notification.application.ComplianceOfficerRecipients;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.MoneyEmailValues;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRepository.ReminderCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * The 12-hour reminder of held transfers (documentations/06 MVP-ADR-24 "Expiry": after 12 hours,
 * compliance officers are notified that the transfer will expire; "Jobs (Q11)"; "Emails (Q9)"
 * {@code TRANSFER_REVIEW_EXPIRING}; MVP-ADR-25; doc 08 §4C.4 criteria 12, 16, 19).
 *
 * <h2>One run</h2>
 *
 * The candidates are the {@code PENDING_REVIEW} transfers held at least {@link
 * TransferReviewProperties#reminderAfter()} before the database time and whose deadline has not
 * come ({@link TransferRepository#findAwaitingReminder(ReminderCandidate, long, int)}), soonest
 * deadline first, read in keyset pages. The active compliance officers are read once per run, and
 * only when a candidate exists ({@link ComplianceOfficerRecipients}, implemented by {@code auth}).
 * For each page one read finds the reminders already in the outbox; a transfer whose officers all
 * have theirs costs nothing more. A page's reads (candidates, officers on first use, recorded
 * reminders) share one short read-only transaction that applies the D14 timeouts first, so a lock
 * wait on any of the three tables ends the run within the configured timeout. The run ends on an
 * empty page, or once {@link TransferReviewReminderProperties#maxPerRun()} transfers got new
 * reminders; transfers already reminded, no longer candidates or failing do not count, so they
 * cannot use up the budget and keep later transfers waiting.
 *
 * <h2>One transfer, one transaction</h2>
 *
 * The D14 timeouts first; the transfer is read again, <b>without a lock</b>, and must still be a
 * candidate; then one {@code TRANSFER_REVIEW_EXPIRING} outbox row per officer still missing one,
 * with the amount, currency and reference only (Q9), under the event id {@link
 * ReviewReminderEventIds#of} of (transfer, officer). The reminder takes no money lock, never waits
 * on or blocks a decision, and never changes a transfer. A decision that commits just after the
 * re-read still gets its reminder; the officer then finds the transfer decided.
 *
 * <p>A transfer that fails rolls back alone (its reminders are written by a later run) and is
 * logged under the alertable key {@code TRANSFER_REVIEW_REMINDER_FAILED}, with the transfer id and
 * the exception's class only; the run goes on. Only a failing read of the run itself (candidates,
 * officers, recorded reminders) ends it early.
 *
 * <h2>Several instances, repeated runs</h2>
 *
 * The event id is deterministic and the outbox's insert does nothing for an id it already holds
 * ({@link NotificationOutbox#enqueueOnce}): a second run, a second instance or a concurrent run
 * adds nothing, and no duplicate raises an error. Officers are written in ascending id order, so
 * concurrent runs wait on each other's rows in one order and cannot deadlock.
 *
 * <h2>Officers who become active later</h2>
 *
 * The id is per officer, so an officer who becomes active while a transfer is still a candidate
 * gets that transfer's reminder on the next run, once; one who is not active when a run reaches the
 * transfer gets none from that run.
 */
@Service
public class TransferReviewReminderService {

  private static final Logger log = LoggerFactory.getLogger(TransferReviewReminderService.class);

  /**
   * The log line of a transfer whose reminders could not be written, with a fixed key an alert can
   * match. The transfer id and the exception's class only.
   */
  static final String FAILURE_LOG =
      "TRANSFER_REVIEW_REMINDER_FAILED: reminder not written, retried by the next run:"
          + " transferId={}, failure={}";

  /**
   * The log line, at WARN, of a run that found held transfers due a reminder but no active
   * compliance officer to receive it, starting with a fixed key an alert can match (owner,
   * 2026-11-14, decision 5), as {@link #FAILURE_LOG}. No transfer id, count or other data: every
   * due transfer is in the same state.
   */
  static final String NO_OFFICER_LOG =
      "TRANSFER_REVIEW_REMINDER_NO_OFFICER: held transfers are due a review reminder, but no"
          + " compliance officer is active";

  /** Candidates read per page; a technical bound of one read, not a business value. */
  static final int PAGE_SIZE = 100;

  private final TransferRepository transfers;
  private final ComplianceOfficerRecipients officers;
  private final NotificationOutbox outbox;
  private final TransactionTimeouts timeouts;
  private final TransferReviewProperties review;
  private final TransferReviewReminderProperties properties;
  private final TransactionTemplate transactions;
  private final TransactionTemplate reads;

  TransferReviewReminderService(
      TransferRepository transfers,
      ComplianceOfficerRecipients officers,
      NotificationOutbox outbox,
      TransactionTimeouts timeouts,
      TransferReviewProperties review,
      TransferReviewReminderProperties properties,
      PlatformTransactionManager transactionManager) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.officers = Objects.requireNonNull(officers, "officers");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    this.review = Objects.requireNonNull(review, "review");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.reads = new TransactionTemplate(transactionManager);
    this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.reads.setReadOnly(true);
  }

  /**
   * One run, as described on the class. Opens its own transactions and refuses to run inside a
   * caller's: joined to an outer transaction, one failing transfer would roll back the others.
   *
   * @return how many transfers got at least one new reminder in this run
   * @throws IllegalTransactionStateException inside a transaction
   */
  public int remindDue() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "the transfer review reminder runs in transactions of its own");
    }
    long started = System.nanoTime();
    long reminderSeconds = review.reminderAfterSeconds();
    List<UUID> recipients = null;
    int reminded = 0;
    int emails = 0;
    int alreadyReminded = 0;
    int skipped = 0;
    int failed = 0;
    ReminderCandidate after = null;
    run:
    while (reminded < properties.maxPerRun()) {
      PageRead read;
      try {
        read = readPage(after, reminderSeconds, recipients);
      } catch (RuntimeException e) {
        log.warn("Transfer review reminder run stopped: failure={}", e.getClass().getSimpleName());
        break;
      }
      if (read.page().isEmpty()) {
        break;
      }
      recipients = read.recipients();
      if (recipients.isEmpty()) {
        log.warn(NO_OFFICER_LOG);
        break;
      }
      for (ReminderCandidate candidate : read.page()) {
        if (reminded >= properties.maxPerRun()) {
          break run;
        }
        after = candidate;
        List<UUID> missing = new ArrayList<>();
        for (UUID officer : recipients) {
          if (!read.recorded().contains(ReviewReminderEventIds.of(candidate.id(), officer))) {
            missing.add(officer);
          }
        }
        if (missing.isEmpty()) {
          alreadyReminded++;
          continue;
        }
        try {
          int written = remind(candidate.id(), missing, reminderSeconds);
          if (written > 0) {
            reminded++;
            emails += written;
          } else {
            skipped++;
          }
        } catch (RuntimeException e) {
          failed++;
          log.warn(FAILURE_LOG, candidate.id(), e.getClass().getSimpleName());
        }
      }
    }
    // Counts only. Logged whenever the run read a candidate, so operators see how many held
    // transfers each run re-reads (alreadyReminded) as well as the work it did.
    if (reminded + alreadyReminded + skipped + failed > 0) {
      log.info(
          "Transfer review reminder run: reminded={}, emails={}, alreadyReminded={}, skipped={},"
              + " failed={}, limitReached={}, durationMs={}",
          reminded,
          emails,
          alreadyReminded,
          skipped,
          failed,
          reminded >= properties.maxPerRun(),
          (System.nanoTime() - started) / 1_000_000);
    }
    return reminded;
  }

  /**
   * One page and what deciding it needs: the candidates, the officers (read on the first page with
   * candidates; empty list if none is active) and the reminders already recorded.
   */
  private record PageRead(
      List<ReminderCandidate> page, List<UUID> recipients, Set<UUID> recorded) {}

  /**
   * Every read of one page in one short read-only transaction, with the D14 timeouts first, so a
   * lock wait on {@code transfer.transfers}, {@code auth.users} or {@code
   * notification.notifications} ends within the configured timeout instead of holding a scheduler
   * thread.
   */
  private PageRead readPage(
      ReminderCandidate after, long reminderSeconds, List<UUID> knownRecipients) {
    return Objects.requireNonNull(
        reads.execute(
            status -> {
              timeouts.apply();
              List<ReminderCandidate> page =
                  transfers.findAwaitingReminder(after, reminderSeconds, PAGE_SIZE);
              if (page.isEmpty()) {
                return new PageRead(page, List.of(), Set.of());
              }
              List<UUID> recipients =
                  knownRecipients != null
                      ? knownRecipients
                      : List.copyOf(officers.activeComplianceOfficers());
              if (recipients.isEmpty()) {
                return new PageRead(page, recipients, Set.of());
              }
              return new PageRead(
                  page, recipients, Set.copyOf(outbox.recorded(eventIds(page, recipients))));
            }));
  }

  private static List<UUID> eventIds(List<ReminderCandidate> page, List<UUID> recipients) {
    List<UUID> ids = new ArrayList<>(page.size() * recipients.size());
    for (ReminderCandidate candidate : page) {
      for (UUID officer : recipients) {
        ids.add(ReviewReminderEventIds.of(candidate.id(), officer));
      }
    }
    return ids;
  }

  /**
   * The reminders of one transfer, in a transaction of their own.
   *
   * @return how many outbox rows this call wrote: 0 if the transfer is no longer a candidate, or
   *     another run wrote them first
   */
  private int remind(UUID transferId, List<UUID> missing, long reminderSeconds) {
    Integer written =
        transactions.execute(
            status -> {
              timeouts.apply();
              Optional<ReminderCandidate> current =
                  transfers.findAwaitingReminder(transferId, reminderSeconds);
              if (current.isEmpty()) {
                return 0;
              }
              Map<String, String> values =
                  MoneyEmailValues.of(
                      current.get().amount(), current.get().currency(), current.get().reference());
              int count = 0;
              for (UUID officer : missing) {
                if (outbox.enqueueOnce(
                    ReviewReminderEventIds.of(transferId, officer),
                    officer,
                    EmailKind.TRANSFER_REVIEW_EXPIRING,
                    values)) {
                  count++;
                }
              }
              return count;
            });
    return written == null ? 0 : written;
  }
}
