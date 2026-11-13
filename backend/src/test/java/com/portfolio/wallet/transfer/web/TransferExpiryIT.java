package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.support.OutboxRows.Row;
import com.portfolio.wallet.transfer.application.TransferExpiryService;
import com.portfolio.wallet.transfer.application.TransferExpiryTestServices;
import com.portfolio.wallet.transfer.persistence.TransferRepository.DueTransfer;
import com.portfolio.wallet.transfer.persistence.TransferRepository.LockMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The expiry job of held transfers (documentations/06 MVP-ADR-24 "Expiry", "Concurrency", "Review
 * outcomes" case 5, "Concurrent staff action and expiry", "Jobs (Q11)"; doc 08 §4C.4 criteria 8,
 * 11, 12, 14a, 19): a due transfer becomes {@code EXPIRED}, its hold and limit are released and
 * neither wallet's total changes, with one review row (case 5), one audit row (actor {@code
 * SYSTEM}) and one {@code TRANSFER_EXPIRED} email; a transfer not yet due is untouched; concurrent
 * runs, a row someone else holds, a racing cancel and a rollback each leave exactly one outcome.
 *
 * <p>The job runs over the whole shared test database, which holds overdue rows of other tests (doc
 * 08 §5: PR 2's test bypass), so every assertion reads only this test's own rows.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferExpiryIT extends TransferReviewFlowSupport {

  private static final long WAIT_SECONDS = 60;

  /** A description no template, log or audit text can contain by chance. */
  private static final String NOTE = "note-e8a4";

  @Autowired TransferExpiryService expiry;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired ApplicationContext context;

  /** As the support's, with the distinctive description. */
  @Override
  UUID held(Account sender, Account recipient) throws Exception {
    MockHttpServletResponse response =
        postTransfer(
            transferBody(numberOf(recipient), HOLD, NOTE),
            sender.token(),
            UUID.randomUUID(),
            randomIp());
    assertThat(response.getStatus()).as("hold").isEqualTo(201);
    assertThat(body(response).get("status").asString()).isEqualTo("PENDING_REVIEW");
    return transferId(response);
  }

  /**
   * Sets a held transfer's deadline to {@code deadline}, held 24 hours before it, as only the
   * superuser can (test only, as {@link #overdue}).
   */
  private static void dueAt(UUID transferId, OffsetDateTime deadline) throws Exception {
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET held_at = CAST(? AS timestamptz) - interval '24 hours',"
                    + " review_expires_at = ? WHERE id = ?",
                deadline,
                deadline,
                transferId));
  }

  /** A deadline in {@code year}, unique to the test, before any other test's overdue rows. */
  private static OffsetDateTime oldDeadline(int year) {
    return OffsetDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        .plusSeconds(ThreadLocalRandom.current().nextInt(1_000_000));
  }

  /** The two ids in PostgreSQL's uuid order (bytewise, not Java's signed comparison). */
  private List<UUID> inDatabaseOrder(UUID a, UUID b) {
    return jdbc.queryForList(
        "SELECT id FROM transfer.transfers WHERE id IN (?, ?) ORDER BY id", UUID.class, a, b);
  }

  private List<Map<String, Object>> reviewAudits(UUID transferId) {
    return jdbc.queryForList(
        "SELECT actor_id, actor_type, result, old_value::text AS old_value,"
            + " new_value::text AS new_value FROM audit.audit_logs"
            + " WHERE action = 'TRANSFER_REVIEW' AND resource_id = ? ORDER BY id",
        transferId.toString());
  }

  private String status(UUID transferId) {
    return (String) transferRow(transferId).get("status");
  }

  private List<String> emailKinds(Account account) {
    return transferEmails(account).stream().map(Row::kind).toList();
  }

  private static Answer<Object> callRealThen(RuntimeException failure) {
    return invocation -> {
      invocation.callRealMethod();
      throw failure;
    };
  }

  /** One expired transfer, exactly once: the state, the money, the review, the audit, the email. */
  private void assertExpiredOnce(UUID id, Account sender, Account recipient) {
    assertThat(status(id)).isEqualTo("EXPIRED");
    assertThat(transferRow(id).get("decided_at")).isNotNull();
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    assertThat(transferUsed(sender)).isEqualByComparingTo("0");
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "EXPIRE")
                    .containsEntry("decided_by", "SYSTEM")
                    .containsEntry("system_reason", "REVIEW_DEADLINE_PASSED"));
    assertThat(reviewAudits(id)).hasSize(1);
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_EXPIRED");
    assertThat(transferEmails(recipient)).isEmpty();
  }

  // The job -------------------------------------------------------------------------------------

  @Test
  void theJobExpiresADueTransferReleasesTheHoldAndRecordsCase5() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    Map<String, Object> before = transferRow(id);
    String reference = (String) before.get("reference");
    assertThat(transferReserved(sender)).isEqualByComparingTo("1300.00");
    overdue(id, 60);

    assertThat(expiry.expireDue()).isPositive();

    assertExpiredOnce(id, sender, recipient);
    // The limit is released on the day the hold reserved it.
    assertThat(transferUsage(sender, ((java.sql.Date) before.get("limit_date")).toLocalDate()))
        .singleElement()
        .satisfies(
            usage -> {
              assertThat((java.math.BigDecimal) usage.get("used")).isEqualByComparingTo("0");
              assertThat((java.math.BigDecimal) usage.get("reserved")).isEqualByComparingTo("0");
            });
    // Review case 5: the system, no requested action, no actor.
    assertThat(reviewRows(id).getFirst())
        .containsEntry("requested_action", null)
        .containsEntry("actor_user_id", null)
        .containsEntry("reason_category", null)
        .containsEntry("reason", null);
    Map<String, Object> audit = reviewAudits(id).getFirst();
    assertThat(audit)
        .containsEntry("actor_id", null)
        .containsEntry("actor_type", "SYSTEM")
        .containsEntry("result", "SUCCESS");
    assertThat((String) audit.get("old_value")).contains("\"status\": \"PENDING_REVIEW\"");
    assertThat((String) audit.get("new_value"))
        .contains("\"action\": \"EXPIRE\"")
        .contains("\"decidedBy\": \"SYSTEM\"")
        .contains("\"systemReason\": \"REVIEW_DEADLINE_PASSED\"")
        .contains("\"status\": \"EXPIRED\"")
        .doesNotContain("requestedAction")
        .doesNotContain(NOTE);
    // The email: amount, currency, reference; sent by the dispatcher.
    Row email = transferEmails(sender).get(1);
    assertThat(email.values()).isEqualTo(emailValues("1300.00", reference));
    EmailMessage message = delivered(sender, email);
    assertThat(message.subject()).isEqualTo("Your transfer has expired");
    assertThat(message.body()).contains("1,300.00 THB", reference);
    assertThat(message.body()).doesNotContain(NOTE, numberOf(recipient), "2000.00", "2,000.00");

    // A second run finds nothing more to do for it.
    expiry.expireDue();
    assertExpiredOnce(id, sender, recipient);
  }

  @Test
  void aTransferNotYetDueIsUntouched() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);

    expiry.expireDue();

    assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("1300.00");
    assertThat(reviewRows(id)).isEmpty();
    assertThat(reviewAudits(id)).isEmpty();
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD");
  }

  /** A frozen or closed wallet does not stop the release, as for the late cancel (MVP-ADR-24). */
  @ParameterizedTest
  @ValueSource(strings = {"sender:FROZEN", "sender:CLOSED", "recipient:FROZEN", "recipient:CLOSED"})
  void anUnavailableWalletDoesNotStopTheRelease(String walletAndStatus) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    String[] parts = walletAndStatus.split(":");
    Account owner = parts[0].equals("sender") ? sender : recipient;
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE wallet.wallets SET status = ? WHERE customer_id = ?",
                parts[1],
                owner.id()));
    overdue(id, 60);

    expiry.expireDue();

    assertExpiredOnce(id, sender, recipient);
  }

  @Test
  void aRunInsideAnotherTransactionIsRefusedAndChangesNothing() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    TransactionTemplate outer = new TransactionTemplate(transactionManager);

    try {
      outer.executeWithoutResult(status -> expiry.expireDue());
      throw new AssertionError("expected IllegalTransactionStateException");
    } catch (IllegalTransactionStateException expected) {
      // refused before it read anything
    }

    assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    assertThat(reviewRows(id)).isEmpty();
  }

  // Failures ------------------------------------------------------------------------------------

  /** One failing transfer is logged by id and class, rolls back alone, and the run goes on. */
  @Test
  void aFailingTransferDoesNotStopTheRunAndIsExpiredByALaterOne(CapturedOutput output)
      throws Exception {
    Account failingSender = funded("2000.00");
    Account failingRecipient = withWallet();
    UUID failing = held(failingSender, failingRecipient);
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID other = held(sender, recipient);
    // The failing one is due first, so the run meets it before the other.
    overdue(failing, 120);
    overdue(other, 60);
    doThrow(new IllegalStateException("injected failure 9d1c"))
        .when(transferRowsSpy)
        .updateDecision(eq(failing), any(), any(), any(), anyLong());
    int start = output.getAll().length();

    expiry.expireDue();

    assertExpiredOnce(other, sender, recipient);
    assertThat(status(failing)).isEqualTo("PENDING_REVIEW");
    assertBalances(failingSender, "700.00", "1300.00");
    assertThat(transferReserved(failingSender)).isEqualByComparingTo("1300.00");
    assertThat(reviewRows(failing)).isEmpty();
    assertThat(reviewAudits(failing)).isEmpty();
    assertThat(emailKinds(failingSender)).containsExactly("TRANSFER_HELD");
    String logs = output.getAll().substring(start);
    assertThat(logs)
        .contains("TRANSFER_EXPIRY_FAILED")
        .contains("transferId=" + failing)
        // The class only (here as translated by the repository proxy), never the message.
        .containsPattern("failure=[A-Za-z]+Exception")
        .doesNotContain("injected failure 9d1c")
        .doesNotContain(NOTE)
        .doesNotContain(failingSender.id().toString());

    org.mockito.Mockito.reset(transferRowsSpy);
    expiry.expireDue();
    assertExpiredOnce(failing, failingSender, failingRecipient);
  }

  /**
   * A failure after the review row, the audit row and the email row were written rolls all of them
   * back with the status change and the release (criterion 14a, "a rolled-back transaction leaves
   * no audit row"; criterion 19).
   */
  @Test
  void aRolledBackExpiryLeavesNoReviewAuditOrEmailRow() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    doAnswer(callRealThen(new IllegalStateException("injected failure")))
        .when(notificationRowsSpy)
        .insert(any(), eq(sender.id()), eq("TRANSFER_EXPIRED"), any(), any());

    expiry.expireDue();

    assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    assertThat(transferRow(id).get("decided_at")).isNull();
    assertBalances(sender, "700.00", "1300.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("1300.00");
    assertThat(reviewRows(id)).isEmpty();
    assertThat(reviewAudits(id)).isEmpty();
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD");

    org.mockito.Mockito.reset(notificationRowsSpy);
    expiry.expireDue();
    assertExpiredOnce(id, sender, recipient);
  }

  /**
   * Security L1 against PostgreSQL: with a maximum of 1 per run, two transfers that fail come first
   * and do not use up the run; the later due transfer still expires in the same run.
   */
  @Test
  void failingTransfersDoNotUseUpTheRunSoALaterDueOneStillExpires(CapturedOutput output)
      throws Exception {
    Account firstSender = funded("2000.00");
    Account secondSender = funded("2000.00");
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID first = held(firstSender, recipient);
    UUID second = held(secondSender, recipient);
    UUID good = held(sender, recipient);
    OffsetDateTime deadline = oldDeadline(1975);
    dueAt(first, deadline);
    dueAt(second, deadline.plusSeconds(1));
    dueAt(good, deadline.plusSeconds(2));
    for (UUID failing : List.of(first, second)) {
      doThrow(new IllegalStateException("injected failure 3c7b"))
          .when(transferRowsSpy)
          .updateDecision(eq(failing), any(), any(), any(), anyLong());
    }
    TransferExpiryService onePerRun = TransferExpiryTestServices.withMaxPerRun(context, 1);
    int start = output.getAll().length();

    assertThat(onePerRun.expireDue()).isEqualTo(1);

    assertExpiredOnce(good, sender, recipient);
    assertThat(status(first)).isEqualTo("PENDING_REVIEW");
    assertThat(status(second)).isEqualTo("PENDING_REVIEW");
    assertThat(reviewRows(first)).isEmpty();
    assertThat(reviewRows(second)).isEmpty();
    String logs = output.getAll().substring(start);
    assertThat(logs)
        .contains("TRANSFER_EXPIRY_FAILED")
        .contains("transferId=" + first)
        .contains("transferId=" + second)
        .doesNotContain("injected failure 3c7b");

    org.mockito.Mockito.reset(transferRowsSpy);
    expiry.expireDue();
    assertExpiredOnce(first, firstSender, recipient);
    assertExpiredOnce(second, secondSender, recipient);
  }

  /**
   * Minor 3, the keyset against PostgreSQL: two of this test's transfers share one deadline; with
   * limit 1 and the cursor, each is returned exactly once, in uuid order, and the cursor is strict.
   */
  @Test
  void dueTransfersArePagedByDeadlineThenIdWithAStrictCursor() throws Exception {
    Account sender = funded("3000.00");
    Account recipient = withWallet();
    UUID a = held(sender, recipient);
    UUID b = held(sender, recipient);
    OffsetDateTime deadline = oldDeadline(1980);
    dueAt(a, deadline);
    dueAt(b, deadline);
    List<UUID> order = inDatabaseOrder(a, b);
    // Just before the shared deadline: no other row lies between this cursor and the two.
    DueTransfer before = new DueTransfer(new UUID(0, 0), deadline.toInstant().minusNanos(1_000));

    List<DueTransfer> firstPage = transferRowsSpy.findDueForExpiry(before, 1);
    List<DueTransfer> secondPage = transferRowsSpy.findDueForExpiry(firstPage.getFirst(), 1);
    List<DueTransfer> thirdPage = transferRowsSpy.findDueForExpiry(secondPage.getFirst(), 1);

    assertThat(firstPage).extracting(DueTransfer::id).containsExactly(order.get(0));
    assertThat(firstPage.getFirst().reviewExpiresAt()).isEqualTo(deadline.toInstant());
    assertThat(secondPage).extracting(DueTransfer::id).containsExactly(order.get(1));
    // Strict: the cursor's own row is never returned again, and nothing of this test follows.
    assertThat(thirdPage).extracting(DueTransfer::id).doesNotContain(a, b);
    assertThat(transferRowsSpy.findDueForExpiry(firstPage.getFirst(), 2))
        .extracting(DueTransfer::id)
        .startsWith(order.get(1))
        .doesNotContain(order.get(0));

    // Leave nothing due behind for other tests.
    expiry.expireDue();
    assertThat(status(a)).isEqualTo("EXPIRED");
    assertThat(status(b)).isEqualTo("EXPIRED");
    assertBalances(sender, "3000.00", "0.00");
  }

  // Several instances (criterion 12) ------------------------------------------------------------

  @Test
  void twoConcurrentRunsExpireEachTransferExactlyOnce() throws Exception {
    int transfers = 3;
    List<Account> senders = new ArrayList<>();
    List<Account> recipients = new ArrayList<>();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < transfers; i++) {
      Account sender = funded("2000.00");
      Account recipient = withWallet();
      senders.add(sender);
      recipients.add(recipient);
      ids.add(held(sender, recipient));
    }
    for (UUID id : ids) {
      overdue(id, 60);
    }

    List<Integer> expired = concurrently(2, expiry::expireDue);

    assertThat(expired).allSatisfy(count -> assertThat(count).isNotNegative());
    for (int i = 0; i < transfers; i++) {
      assertExpiredOnce(ids.get(i), senders.get(i), recipients.get(i));
    }
  }

  /** {@code SKIP LOCKED}: the run neither waits nor writes; a later run expires it. */
  @Test
  void aRowLockedByAnotherTransactionIsSkippedAndExpiredByTheNextRun() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection holder = openTransaction()) {
      try (PreparedStatement lock =
          holder.prepareStatement("SELECT id FROM transfer.transfers WHERE id = ? FOR UPDATE")) {
        lock.setObject(1, id);
        try (ResultSet rows = lock.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }

      // Bounded: a run that waited on the lock would not finish before the holder lets go.
      Future<Integer> run = pool.submit(expiry::expireDue);
      run.get(WAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(status(id)).isEqualTo("PENDING_REVIEW");
      assertThat(reviewRows(id)).isEmpty();
      assertThat(reviewAudits(id)).isEmpty();
      assertBalances(sender, "700.00", "1300.00");
      holder.rollback();
    } finally {
      pool.shutdownNow();
    }

    expiry.expireDue();

    assertExpiredOnce(id, sender, recipient);
  }

  // Cancel against expiry (criterion 14a) -------------------------------------------------------

  /**
   * The job holds the transfer row: the cancel waits for it, then finds the transfer {@code
   * EXPIRED} and answers {@code 409} without writing anything; the outcome is the job's (case 5).
   */
  @Test
  void aCancelWaitingOnTheJobsLockGets409AndWritesNothing() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object row = invocation.callRealMethod();
              locked.countDown();
              assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
              return row;
            })
        .when(transferRowsSpy)
        .lockForUpdate(id, LockMode.SKIP_LOCKED);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> job = pool.submit(expiry::expireDue);
      assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Future<MockHttpServletResponse> cancel = pool.submit(() -> cancel(id, sender));
      awaitLockWaiter();
      release.countDown();

      job.get(WAIT_SECONDS, TimeUnit.SECONDS);
      MockHttpServletResponse response = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(errorCode(response)).isEqualTo("INVALID_TRANSFER_STATE");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    assertExpiredOnce(id, sender, recipient);
    assertThat(reviewRows(id).getFirst()).containsEntry("requested_action", null);
    assertThat(reviewAudits(id).getFirst()).containsEntry("actor_type", "SYSTEM");
  }

  /**
   * The cancel holds the transfer row: the job skips it without waiting, and the cancel's own
   * outcome stands (after the deadline, case 7).
   */
  @Test
  void theJobSkipsATransferWhoseCancelHoldsTheLock() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object row = invocation.callRealMethod();
              locked.countDown();
              assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
              return row;
            })
        .when(transferRowsSpy)
        .lockSentBy(id, sender.id());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<MockHttpServletResponse> cancel = pool.submit(() -> cancel(id, sender));
      assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

      pool.submit(expiry::expireDue).get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertThat(status(id)).isEqualTo("PENDING_REVIEW");
      assertThat(reviewRows(id)).isEmpty();

      release.countDown();
      MockHttpServletResponse response = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertThat(response.getStatus()).isEqualTo(409);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    expiry.expireDue();
    assertExpiredOnce(id, sender, recipient);
    assertThat(reviewRows(id).getFirst())
        .containsEntry("requested_action", "CANCEL")
        .containsEntry("actor_user_id", sender.id());
    assertThat(reviewAudits(id).getFirst()).containsEntry("actor_type", "CUSTOMER");
  }

  /**
   * Cancel and job started together, right at the deadline: whichever wins, there is exactly one
   * final outcome, one terminal state, one review row, one audit row and one email, and the hold is
   * released once. Repeated, so both orders and both sides of the deadline get their chance.
   */
  @Test
  void aCancelRacingTheJobAtTheDeadlineEndsWithExactlyOneOutcome() throws Exception {
    for (int round = 0; round < 4; round++) {
      Account sender = funded("2000.00");
      Account recipient = withWallet();
      UUID id = held(sender, recipient);
      // Rounds 0 and 1: the deadline one second ahead; rounds 2 and 3: just passed.
      overdue(id, round < 2 ? -1 : 0);
      Instant deadline =
          ((OffsetDateTime)
                  jdbc.queryForObject(
                      "SELECT review_expires_at FROM transfer.transfers WHERE id = ?",
                      OffsetDateTime.class,
                      id))
              .toInstant();
      CyclicBarrier start = new CyclicBarrier(2);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      MockHttpServletResponse response;
      try {
        Future<Integer> job =
            pool.submit(
                () -> {
                  start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                  sleepUntil(deadline);
                  return expiry.expireDue();
                });
        Future<MockHttpServletResponse> cancel =
            pool.submit(
                () -> {
                  start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                  sleepUntil(deadline);
                  return cancel(id, sender);
                });
        job.get(WAIT_SECONDS, TimeUnit.SECONDS);
        response = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }
      // A job that ran just before the deadline found nothing due; the next run settles it.
      expiry.expireDue();

      String status = status(id);
      assertThat(status).isIn("CANCELLED", "EXPIRED");
      assertThat(response.getStatus()).isEqualTo("CANCELLED".equals(status) ? 200 : 409);
      assertThat(reviewRows(id)).hasSize(1);
      assertThat(reviewAudits(id)).hasSize(1);
      assertThat(emailKinds(sender))
          .containsExactly(
              "TRANSFER_HELD",
              "CANCELLED".equals(status) ? "TRANSFER_CANCELLED" : "TRANSFER_EXPIRED");
      assertThat(transferEmails(recipient)).isEmpty();
      assertBalances(sender, "2000.00", "0.00");
      assertBalances(recipient, "0.00", "0.00");
      assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    }
  }

  // Helpers -------------------------------------------------------------------------------------

  private static void sleepUntil(Instant instant) throws InterruptedException {
    long millis = Duration.between(Instant.now(), instant).toMillis();
    if (millis > 0) {
      Thread.sleep(millis);
    }
  }

  /** Waits until some session of the runtime role waits on a row lock of the transfers table. */
  private void awaitLockWaiter() {
    await()
        .atMost(Duration.ofSeconds(WAIT_SECONDS))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND query LIKE '%transfer.transfers%'",
                        Integer.class)
                    > 0);
  }

  private static <T> List<T> concurrently(int threads, Callable<T> work) throws Exception {
    CyclicBarrier start = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                  return work.call();
                }));
      }
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
