package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.support.OutboxRows;
import com.portfolio.wallet.support.PostgresTestDatabase;
import com.portfolio.wallet.transfer.application.ReviewReminderTestIds;
import com.portfolio.wallet.transfer.application.TransferReviewReminderService;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The 12-hour review reminder against PostgreSQL (documentations/06 MVP-ADR-24 "Expiry", "Jobs
 * (Q11)", "Emails (Q9)"; MVP-ADR-25; doc 08 §4C.4 criteria 12, 16, 19): a transfer held 12 hours or
 * more and not yet at its deadline gets exactly one {@code TRANSFER_REVIEW_EXPIRING} row per active
 * compliance officer, under the deterministic event id; a re-run, a concurrent run or a later run
 * adds nothing; a younger, decided or overdue transfer and an inactive officer get none; the
 * reminder changes no transfer and no money; a failing transfer is isolated; the dispatcher sends
 * the amount, currency and reference and nothing else.
 *
 * <p>The job runs over the whole shared test database, which holds other tests' transfers and
 * officers (doc 08 §5), so every assertion reads only this test's own transfers' rows (by their
 * event ids or references). Each test moves its still-held transfers past their deadline at the
 * end, so later runs no longer remind them.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferReviewReminderIT extends TransferReviewFlowSupport {

  /** A description no template, log or audit text can contain by chance. */
  private static final String NOTE = "note-r7c3";

  private static final String KIND = "TRANSFER_REVIEW_EXPIRING";

  @Autowired TransferReviewReminderService reminders;

  private final List<UUID> heldHere = new ArrayList<>();

  @AfterEach
  void noLongerCandidates() throws Exception {
    for (UUID id : heldHere) {
      if ("PENDING_REVIEW".equals(transferRow(id).get("status"))) {
        overdue(id, 60);
      }
    }
  }

  /** A held transfer with the distinctive description. */
  private UUID heldWithNote(Account sender, Account recipient) throws Exception {
    MockHttpServletResponse response =
        postTransfer(
            transferBody(numberOf(recipient), HOLD, NOTE),
            sender.token(),
            UUID.randomUUID(),
            randomIp());
    assertThat(response.getStatus()).as("hold").isEqualTo(201);
    UUID id = transferId(response);
    heldHere.add(id);
    return id;
  }

  /**
   * Moves the hold back so that it was held {@code seconds} before the database's now, with the
   * deadline the usual 24 hours after it, as only the superuser can (the V4 guard keeps both times
   * immutable): test only, as {@link #overdue}.
   */
  private static void heldAgo(UUID transferId, long seconds) throws Exception {
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET held_at = now() - make_interval(secs => ?),"
                    + " review_expires_at = now() - make_interval(secs => ?) + interval '24 hours'"
                    + " WHERE id = ?",
                (double) seconds,
                (double) seconds,
                transferId));
  }

  private static final long TWELVE_HOURS = Duration.ofHours(12).toSeconds();

  /** The transfer's reminder rows: recipient → event id. */
  private Map<UUID, UUID> reminders(UUID transferId) {
    String reference = (String) transferRow(transferId).get("reference");
    Map<UUID, UUID> rows = new java.util.HashMap<>();
    jdbc.query(
        "SELECT user_id, event_id FROM notification.notifications"
            + " WHERE kind = ? AND payload ->> 'reference' = ?",
        rs -> {
          UUID user = rs.getObject("user_id", UUID.class);
          assertThat(rows.put(user, rs.getObject("event_id", UUID.class)))
              .as("one reminder per officer")
              .isNull();
        },
        KIND,
        reference);
    return rows;
  }

  private Set<UUID> activeOfficers() {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT id FROM auth.users WHERE role = 'COMPLIANCE_OFFICER' AND status = 'ACTIVE'",
            UUID.class));
  }

  private void assertOneReminderPerActiveOfficer(UUID transferId) {
    Map<UUID, UUID> rows = reminders(transferId);
    assertThat(rows.keySet()).isEqualTo(activeOfficers());
    rows.forEach(
        (officer, event) ->
            assertThat(event).isEqualTo(ReviewReminderTestIds.of(transferId, officer)));
  }

  // The reminder --------------------------------------------------------------------------------

  @Test
  void aTransferHeld12HoursGetsOneReminderPerActiveOfficerAndChangesNothing() throws Exception {
    Account officer = officer();
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = heldWithNote(sender, recipient);
    heldAgo(id, TWELVE_HOURS);
    Map<String, Object> before = transferRow(id);

    assertThat(reminders.remindDue()).isPositive();

    assertOneReminderPerActiveOfficer(id);
    assertThat(reminders(id)).containsKey(officer.id());
    // Only staff: the parties get no reminder, and nothing else is written for them.
    assertThat(reminders(id)).doesNotContainKeys(sender.id(), recipient.id());
    assertThat(OutboxRows.of(jdbc, officer.id(), KIND))
        .filteredOn(row -> row.values().get("reference").equals(before.get("reference")))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.status()).isEqualTo("PENDING");
              assertThat(row.values())
                  .isEqualTo(emailValues("1300.00", (String) before.get("reference")));
            });
    // The transfer, the hold and the money are untouched; no review or audit row.
    assertThat(transferRow(id)).isEqualTo(before);
    assertBalances(sender, "700.00", "1300.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(reviewRows(id)).isEmpty();
  }

  @Test
  void aTransferHeldLessThan12HoursGetsNone() throws Exception {
    Account sender = funded("2000.00");
    UUID id = heldWithNote(sender, withWallet());
    heldAgo(id, TWELVE_HOURS - 60);

    reminders.remindDue();

    assertThat(reminders(id)).isEmpty();
  }

  /** Only status ACTIVE can sign in; frozen, suspended and unverified officers get nothing. */
  @Test
  void anInactiveOfficerGetsNone() throws Exception {
    List<Account> inactive = new ArrayList<>();
    for (String status : List.of("FROZEN", "SUSPENDED", "PENDING_VERIFICATION")) {
      Account officer = officer();
      jdbc.update("UPDATE auth.users SET status = ? WHERE id = ?", status, officer.id());
      inactive.add(officer);
    }
    Account active = officer();
    Account sender = funded("2000.00");
    UUID id = heldWithNote(sender, withWallet());
    heldAgo(id, TWELVE_HOURS);

    reminders.remindDue();

    assertThat(reminders(id)).containsKey(active.id());
    for (Account officer : inactive) {
      assertThat(reminders(id)).doesNotContainKey(officer.id());
      assertThat(OutboxRows.of(jdbc, officer.id(), KIND)).isEmpty();
    }
    assertOneReminderPerActiveOfficer(id);
  }

  /**
   * "Never twice" with an officer activated later (owner, 2026-11-14, decision 2): run 1 reminds
   * the officers active then; the officer is activated; run 2 writes exactly one row, theirs; runs
   * 3 and 4 add nothing for anyone.
   */
  @Test
  void anOfficerActivatedLaterGetsExactlyOneAndLaterRunsAddNothing() throws Exception {
    Account later = officer();
    jdbc.update("UPDATE auth.users SET status = 'PENDING_VERIFICATION' WHERE id = ?", later.id());
    Account sender = funded("2000.00");
    UUID id = heldWithNote(sender, withWallet());
    heldAgo(id, TWELVE_HOURS + 3600);

    // Run 1: the officers active now.
    reminders.remindDue();
    Map<UUID, UUID> afterRun1 = reminders(id);
    assertThat(afterRun1).isNotEmpty().doesNotContainKey(later.id());
    assertOneReminderPerActiveOfficer(id);

    jdbc.update("UPDATE auth.users SET status = 'ACTIVE' WHERE id = ?", later.id());

    // Run 2: exactly one new row, for the officer activated in between.
    reminders.remindDue();
    Map<UUID, UUID> afterRun2 = reminders(id);
    Map<UUID, UUID> added = new java.util.HashMap<>(afterRun2);
    afterRun1.keySet().forEach(added::remove);
    assertThat(added).containsOnly(Map.entry(later.id(), ReviewReminderTestIds.of(id, later.id())));
    assertThat(afterRun2).containsAllEntriesOf(afterRun1);
    assertThat(OutboxRows.of(jdbc, later.id(), KIND))
        .filteredOn(row -> row.eventId().equals(ReviewReminderTestIds.of(id, later.id())))
        .hasSize(1);

    // Runs 3 and 4: nothing for anyone.
    reminders.remindDue();
    reminders.remindDue();
    assertThat(reminders(id)).isEqualTo(afterRun2);
    assertOneReminderPerActiveOfficer(id);
  }

  /** Two runs at once (two instances): one row per officer, and neither run fails. */
  @Test
  void concurrentRunsWriteEachReminderOnce(CapturedOutput output) throws Exception {
    Account sender = funded("4000.00");
    Account recipient = withWallet();
    List<UUID> ids = List.of(heldWithNote(sender, recipient), heldWithNote(sender, recipient));
    for (UUID id : ids) {
      heldAgo(id, TWELVE_HOURS);
    }
    int runs = 4;
    CyclicBarrier start = new CyclicBarrier(runs);
    ExecutorService pool = Executors.newFixedThreadPool(runs);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < runs; i++) {
        Callable<Integer> run =
            () -> {
              start.await(30, TimeUnit.SECONDS);
              return reminders.remindDue();
            };
        results.add(pool.submit(run));
      }
      for (Future<Integer> result : results) {
        result.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    for (UUID id : ids) {
      assertOneReminderPerActiveOfficer(id);
    }
    assertThat(output).doesNotContain("TRANSFER_REVIEW_REMINDER_FAILED");
  }

  /** Cancelled, or past its deadline though not yet expired by the job: no reminder. */
  @Test
  void aDecidedOrOverdueTransferGetsNone() throws Exception {
    Account sender = funded("4000.00");
    Account recipient = withWallet();
    UUID cancelled = heldWithNote(sender, recipient);
    heldAgo(cancelled, TWELVE_HOURS + 60);
    assertThat(cancel(cancelled, sender).getStatus()).isEqualTo(200);
    UUID overdue = heldWithNote(sender, recipient);
    overdue(overdue, 1);

    reminders.remindDue();

    assertThat(reminders(cancelled)).isEmpty();
    assertThat(reminders(overdue)).isEmpty();
    assertThat(transferRow(overdue).get("status")).isEqualTo("PENDING_REVIEW");
  }

  /** One transfer whose rows cannot be written rolls back alone; the next run writes them. */
  @Test
  void aFailingTransferIsIsolatedAndRemindedByTheNextRun(CapturedOutput output) throws Exception {
    Account sender = funded("4000.00");
    Account recipient = withWallet();
    UUID failing = heldWithNote(sender, recipient);
    UUID next = heldWithNote(sender, recipient);
    heldAgo(failing, TWELVE_HOURS + 120);
    heldAgo(next, TWELVE_HOURS);
    String failingReference = (String) transferRow(failing).get("reference");
    // The first officer's row is written, then the second insert fails: the whole transfer rolls
    // back.
    int[] inserts = {0};
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Map<String, String> values = invocation.getArgument(3);
              if (failingReference.equals(values.get("reference")) && ++inserts[0] == 2) {
                throw new DataAccessResourceFailureException("secret detail 81b0");
              }
              return invocation.callRealMethod();
            })
        .when(notificationRowsSpy)
        .insertIfAbsent(any(), any(), eq(KIND), any(), any());

    reminders.remindDue();

    assertThat(reminders(failing)).isEmpty();
    assertOneReminderPerActiveOfficer(next);
    assertThat(output)
        .contains("TRANSFER_REVIEW_REMINDER_FAILED")
        .contains("transferId=" + failing)
        .contains("failure=DataAccessResourceFailureException")
        .doesNotContain("secret detail 81b0")
        .doesNotContain(failingReference);

    org.mockito.Mockito.reset(notificationRowsSpy);
    reminders.remindDue();

    assertOneReminderPerActiveOfficer(failing);
  }

  /** Sent after commit to the officer's address: amount, currency, reference, nothing more. */
  @Test
  void theDispatcherSendsTheReminderWithTheQ9ValuesOnly() throws Exception {
    Account officer = officer();
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = heldWithNote(sender, recipient);
    heldAgo(id, TWELVE_HOURS);
    String reference = (String) transferRow(id).get("reference");
    reminders.remindDue();
    OutboxRows.Row row =
        OutboxRows.of(jdbc, officer.id(), KIND).stream()
            .filter(r -> reference.equals(r.values().get("reference")))
            .findFirst()
            .orElseThrow();

    EmailMessage message = delivered(officer, row);

    assertThat(message.to()).isEqualTo(address(officer));
    assertThat(message.subject()).isEqualTo("A transfer waiting for review will expire");
    assertThat(message.body())
        .contains("1,300.00 THB", "Reference: " + reference, "(Asia/Bangkok time)", "will expire");
    String text = message.subject() + " " + message.body();
    assertThat(text)
        .doesNotContain(NOTE)
        .doesNotContain("Somchai")
        .doesNotContain("Jaidee")
        .doesNotContain(numberOf(sender))
        .doesNotContain(numberOf(recipient))
        .doesNotContain(sender.id().toString())
        .doesNotContain(id.toString());
    assertThat(text.toLowerCase(java.util.Locale.ROOT))
        .doesNotContain("score", "rule", "risk", "balance");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notification.notifications WHERE event_id = ?",
                String.class,
                row.eventId()))
        .isEqualTo("SENT");
  }

  /**
   * D14 on the run's reads: while another session holds {@code ACCESS EXCLUSIVE} on the table of
   * the officer read ({@code auth.users}) or of the recorded-reminders read ({@code
   * notification.notifications}), the run ends after the configured lock timeout (5 s) instead of
   * waiting, logs the exception class only, writes nothing, and the next run reminds normally.
   */
  @ParameterizedTest
  @ValueSource(strings = {"auth.users", "notification.notifications"})
  void aLockedReadEndsTheRunWithinTheLockTimeout(String table, CapturedOutput output)
      throws Exception {
    Account sender = funded("2000.00");
    UUID id = heldWithNote(sender, withWallet());
    heldAgo(id, TWELVE_HOURS);
    int reminded;
    long took;
    try (Connection superuser = PostgresTestDatabase.openBootstrapSuperuserConnection()) {
      superuser.setAutoCommit(false);
      update(superuser, "LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
      long start = System.nanoTime();
      reminded = reminders.remindDue();
      took = Duration.ofNanos(System.nanoTime() - start).toMillis();
      superuser.rollback();
    }

    assertThat(reminded).isZero();
    assertThat(took).as("ended by the 5 s lock timeout").isLessThan(15_000);
    assertThat(output)
        .contains("Transfer review reminder run stopped: failure=")
        .doesNotContain("LOCK TABLE")
        .doesNotContain("lock timeout");
    assertThat(reminders(id)).isEmpty();

    reminders.remindDue();
    assertOneReminderPerActiveOfficer(id);
  }

  // Send-time recipient check (owner, 2026-11-14, decision 8) ----------------------------------

  private Map<String, Object> outboxRow(UUID eventId) {
    return jdbc.queryForMap(
        "SELECT status, attempts, last_failure, sent_at FROM notification.notifications"
            + " WHERE event_id = ?",
        eventId);
  }

  private boolean mailedTo(Account account) {
    return mail.messages().stream().anyMatch(m -> m.to().equals(address(account)));
  }

  /** The row is final without a send: FAILED, one claim, the fixed reason, never claimed again. */
  private void assertFinalUnsent(UUID eventId, Account recipient) {
    assertThat(outboxRow(eventId))
        .containsEntry("status", "FAILED")
        .containsEntry("attempts", 1)
        .containsEntry("last_failure", "RecipientNotActive")
        .containsEntry("sent_at", null);
    assertThat(mailedTo(recipient)).isFalse();
    assertThat(outboxDispatcher.dispatch(eventId)).as("never claimed again").isFalse();
    assertThat(mailedTo(recipient)).isFalse();
  }

  @Test
  void anOfficerDeactivatedAfterTheReminderWasWrittenGetsNoEmail(CapturedOutput output)
      throws Exception {
    Account officer = officer();
    Account sender = funded("2000.00");
    UUID id = heldWithNote(sender, withWallet());
    heldAgo(id, TWELVE_HOURS);
    reminders.remindDue();
    UUID event = ReviewReminderTestIds.of(id, officer.id());
    assertThat(outboxRow(event)).containsEntry("status", "PENDING");
    jdbc.update("UPDATE auth.users SET status = 'SUSPENDED' WHERE id = ?", officer.id());

    assertThat(outboxDispatcher.dispatch(event)).as("claimed").isTrue();

    assertFinalUnsent(event, officer);
    assertThat(output)
        .contains("recipient not active, not sent: eventId=" + event)
        .doesNotContain(address(officer));
  }

  @Test
  void aCustomerNoLongerActiveGetsNoOutboxEmail() throws Exception {
    Account sender = funded("2000.00");
    heldWithNote(sender, withWallet());
    OutboxRows.Row held =
        OutboxRows.of(jdbc, sender.id(), "TRANSFER_HELD").stream().findFirst().orElseThrow();
    jdbc.update("UPDATE auth.users SET status = 'FROZEN' WHERE id = ?", sender.id());

    assertThat(outboxDispatcher.dispatch(held.eventId())).as("claimed").isTrue();

    assertFinalUnsent(held.eventId(), sender);
  }

  /** ACTIVE recipients are unaffected: the customer's and the officer's emails are sent. */
  @Test
  void activeRecipientsStillGetTheirEmails() throws Exception {
    Account officer = officer();
    Account sender = funded("2000.00");
    UUID id = heldWithNote(sender, withWallet());
    heldAgo(id, TWELVE_HOURS);
    reminders.remindDue();
    OutboxRows.Row held =
        OutboxRows.of(jdbc, sender.id(), "TRANSFER_HELD").stream().findFirst().orElseThrow();
    UUID reminder = ReviewReminderTestIds.of(id, officer.id());

    assertThat(delivered(sender, held).subject()).isEqualTo("Your transfer is waiting for review");
    assertThat(outboxDispatcher.dispatch(reminder)).isTrue();

    assertThat(outboxRow(held.eventId())).containsEntry("status", "SENT");
    assertThat(outboxRow(reminder)).containsEntry("status", "SENT");
    assertThat(mailedTo(officer)).isTrue();
  }

  /** The officer helper makes a staff account; the reminder never goes to a customer. */
  @Test
  void customersAreNeverRecipients() throws Exception {
    Account customer = account(Role.CUSTOMER);
    Account sender = funded("2000.00");
    UUID id = heldWithNote(sender, withWallet());
    heldAgo(id, TWELVE_HOURS);

    reminders.remindDue();

    assertThat(reminders(id)).isNotEmpty().doesNotContainKey(customer.id());
    assertThat(OutboxRows.of(jdbc, customer.id(), KIND)).isEmpty();
  }
}
