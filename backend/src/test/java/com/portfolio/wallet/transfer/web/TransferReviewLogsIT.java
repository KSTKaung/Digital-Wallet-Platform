package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.portfolio.wallet.admin.application.ReconciliationReport;
import com.portfolio.wallet.admin.application.ReconciliationService;
import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.support.OutboxRows;
import com.portfolio.wallet.transfer.application.ReviewReminderTestIds;
import com.portfolio.wallet.transfer.application.TransferReviewReminderService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Data exposure on the PR 5b paths (documentations/08 §4C.4 criterion 20; doc 06 MVP-ADR-24 "Staff
 * decisions": the reviewer's reason is "review row only, never in audit rows, emails, logs or
 * customer responses"; "Security implications": scores, rule hits and review history are staff
 * only; MVP-ADR-03 "Alerts": reconciliation lines carry ids only; PR 5b step 13).
 *
 * <p>One sweep drives every staff decision (approval, staff rejection with a reason, system
 * rejection of an unavailable wallet, a late approval and a late rejection with a reason), a staff
 * decision that fails inside its transaction (logged at ERROR), the queue and the detail, the
 * 12-hour reminder and its delivery, the customers' transfer and history reads, the emails of the
 * decisions and a reconciliation run, then checks every log line written meanwhile, every customer
 * response, every audit row and every outbox payload and delivered email of the test's own rows.
 *
 * <p>Own rows only on the shared database: the reminder and the reconciliation read every row, and
 * other tests' rows may make them log; every check is about values this test created.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferReviewLogsIT extends TransferReviewFlowSupport {

  /** A description and a reviewer's reason no template, log or audit text can contain by chance. */
  private static final String NOTE = "note-7e2a private";

  private static final String REASON = "Mule pattern reason-91fd";
  private static final String REASON_MARK = "reason-91fd";

  @Autowired TransferReviewReminderService reminders;
  @Autowired ReconciliationService reconciliation;

  private UUID heldWithNote(Account sender, Account recipient) throws Exception {
    MockHttpServletResponse response =
        postTransfer(
            transferBody(numberOf(recipient), HOLD, NOTE),
            sender.token(),
            UUID.randomUUID(),
            randomIp());
    assertThat(response.getStatus()).as("hold").isEqualTo(201);
    return transferId(response);
  }

  private void setWalletStatus(Account owner, String status) throws Exception {
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE wallet.wallets SET status = ? WHERE customer_id = ?",
                status,
                owner.id()));
  }

  private void heldTwelveHoursAgo(UUID id) throws Exception {
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET held_at = now() - interval '12 hours 2 minutes',"
                    + " review_expires_at = now() + interval '11 hours 58 minutes' WHERE id = ?",
                id));
  }

  private String reference(UUID id) {
    return (String) transferRow(id).get("reference");
  }

  @Test
  void staffReviewReminderHistoryAndReconciliationExposeNothingSensitive(CapturedOutput output)
      throws Exception {
    Account sender = funded("10000.00");
    Account recipient = withWallet();
    Account unavailable = withWallet();
    Account reviewer = officer();
    // Every hold before any completion to the same recipient (the test policy, see the support).
    UUID approved = heldWithNote(sender, recipient);
    UUID rejected = heldWithNote(sender, recipient);
    UUID systemRejected = heldWithNote(sender, unavailable);
    UUID lateApproved = heldWithNote(sender, recipient);
    UUID lateRejected = heldWithNote(sender, recipient);
    UUID reminded = heldWithNote(sender, recipient);
    UUID failing = heldWithNote(sender, recipient);
    List<UUID> own =
        List.of(approved, rejected, systemRejected, lateApproved, lateRejected, reminded, failing);
    List<MockHttpServletResponse> staff = new ArrayList<>();
    List<MockHttpServletResponse> customers = new ArrayList<>();
    int start = output.getAll().length();

    // Staff reads and decisions.
    staff.add(getAs(REVIEWS + "?size=100", reviewer.token()));
    staff.add(reviewDetail(approved, reviewer));
    MockHttpServletResponse approval = approveReview(approved, reviewer);
    assertThat(approval.getStatus()).as(approval.getContentAsString()).isEqualTo(200);
    staff.add(approval);
    MockHttpServletResponse rejection = rejectReview(rejected, REASON, reviewer);
    assertThat(rejection.getStatus()).as(rejection.getContentAsString()).isEqualTo(200);
    staff.add(rejection);
    setWalletStatus(unavailable, "FROZEN");
    MockHttpServletResponse systemRejection = approveReview(systemRejected, reviewer);
    assertThat(body(systemRejection).get("status").asString()).isEqualTo("REJECTED");
    staff.add(systemRejection);
    overdue(lateApproved, 60);
    overdue(lateRejected, 60);
    staff.add(approveReview(lateApproved, reviewer));
    staff.add(rejectReview(lateRejected, REASON, reviewer));
    assertThat(staff.subList(staff.size() - 2, staff.size()))
        .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(409));
    // A staff rejection that fails inside its transaction: 500, logged at ERROR, nothing kept.
    doThrow(new DataIntegrityViolationException("injected")).when(auditRowsSpy).insert(any());
    MockHttpServletResponse failed = rejectReview(failing, REASON, reviewer);
    assertThat(failed.getStatus()).isEqualTo(500);
    staff.add(failed);
    Mockito.reset(auditRowsSpy);
    assertThat(transferRow(failing)).containsEntry("status", "PENDING_REVIEW");
    assertThat(reviewRows(failing)).isEmpty();
    staff.add(reviewDetail(rejected, reviewer));
    staff.add(reviewDetail(lateRejected, reviewer));

    // The reminder and its delivery to this reviewer.
    heldTwelveHoursAgo(reminded);
    reminders.remindDue();
    UUID reminderEvent = ReviewReminderTestIds.of(reminded, reviewer.id());
    assertThat(outboxDispatcher.dispatch(reminderEvent)).as("reminder claimed").isTrue();

    // The customers' reads: transfers and history, list, detail and status.
    for (Account customer : List.of(sender, recipient, unavailable)) {
      customers.add(getAs(TRANSFERS + "?size=100", customer.token()));
      customers.add(getAs("/api/v1/transactions?size=100", customer.token()));
      for (UUID id : own) {
        customers.add(getAs(TRANSFERS + "/" + id, customer.token()));
        customers.add(getAs(TRANSFERS + "/" + id + "/status", customer.token()));
      }
    }

    // The decisions' emails, delivered.
    List<EmailMessage> delivered = new ArrayList<>();
    for (Account customer : List.of(sender, recipient)) {
      for (OutboxRows.Row row : transferEmails(customer)) {
        if (!row.kind().equals("TRANSFER_HELD")) {
          delivered.add(delivered(customer, row));
        }
      }
    }
    delivered.add(
        mail.messages().stream()
            .filter(m -> m.to().equals(address(reviewer)))
            .filter(m -> m.kind().name().equals("TRANSFER_REVIEW_EXPIRING"))
            .reduce((first, second) -> second)
            .orElseThrow());

    // Reconciliation over the shared database: the test's own rows are consistent.
    ReconciliationReport report = reconciliation.reconcile();
    assertThat(report.completed()).isTrue();
    for (UUID id : own) {
      assertThat(report.involving(id)).as(id.toString()).isEmpty();
    }

    String logged = output.getAll().substring(start);
    assertThat(logged).as("captured output").isNotEmpty();

    // Logs: no description, reason, wallet number, legal name, score, rule or party id.
    List<String> secrets = new ArrayList<>();
    secrets.add(NOTE);
    secrets.add("note-7e2a");
    secrets.add(REASON_MARK);
    for (Account party : List.of(sender, recipient, unavailable)) {
      secrets.add(numberOf(party));
      secrets.add(party.id().toString());
      secrets.add(walletId(party).toString());
    }
    secrets.add("Jaidee");
    secrets.add("TRANSFER_AMOUNT");
    secrets.add("NEW_RECIPIENT");
    secrets.add("NEW_SENDER_WALLET");
    secrets.add("TRANSFER_FREQUENCY");
    for (String secret : secrets) {
      assertThat(logged).as("log must not contain a sensitive value").doesNotContain(secret);
    }
    assertThat(logged).doesNotContainIgnoringCase("\"score\"");
    // Reconciliation lines: ids and fixed names only, never this test's amounts or references.
    for (String line : logged.split("\\R")) {
      if (line.contains("RECONCILIATION_")) {
        assertThat(line).doesNotContain("1300.00", "1,300.00");
        for (UUID id : own) {
          assertThat(line).doesNotContain(reference(id)).doesNotContain(id.toString());
        }
      }
    }

    // Customer responses: no description, reason, score, rule, review fact or reviewer.
    for (MockHttpServletResponse response : customers) {
      assertThat(response.getContentAsString())
          .doesNotContain("note-7e2a")
          .doesNotContain(REASON_MARK)
          .doesNotContain("description")
          .doesNotContain("score")
          .doesNotContain("TRANSFER_AMOUNT")
          .doesNotContain("NEW_RECIPIENT")
          .doesNotContain("NEW_SENDER_WALLET")
          .doesNotContain("TRANSFER_FREQUENCY")
          .doesNotContain("STAFF_REJECTION")
          .doesNotContain("SENDER_WALLET_")
          .doesNotContain("RECIPIENT_WALLET_")
          .doesNotContain("REVIEW_DEADLINE_PASSED")
          .doesNotContain("decidedBy")
          .doesNotContain("riskAssessment")
          .doesNotContain(reviewer.id().toString())
          .doesNotContain("Jaidee")
          .doesNotContain(numberOf(sender))
          .doesNotContain(numberOf(recipient))
          .doesNotContain(numberOf(unavailable));
    }
    // Staff responses: the reason only in the review history; never the description or a full
    // wallet number.
    for (MockHttpServletResponse response : staff) {
      assertThat(response.getContentAsString())
          .doesNotContain("note-7e2a")
          .doesNotContain("description")
          .doesNotContain(numberOf(sender))
          .doesNotContain(numberOf(recipient))
          .doesNotContain(numberOf(unavailable));
    }
    JsonNode lateHistory = body(reviewDetail(lateRejected, reviewer)).get("history");
    assertThat(lateHistory.toString())
        .as("a late reject drops its reason")
        .doesNotContain(REASON_MARK);

    // History: only the approval is a movement; the rejected, expired and held transfers are not.
    List<String> senderHistory = new ArrayList<>();
    body(getAs("/api/v1/transactions?size=100", sender.token()))
        .get("items")
        .forEach(item -> senderHistory.add(item.get("reference").asString()));
    assertThat(senderHistory).contains(reference(approved));
    for (UUID id :
        List.of(rejected, systemRejected, lateApproved, lateRejected, reminded, failing)) {
      assertThat(senderHistory).doesNotContain(reference(id));
    }
    assertThat(body(getAs("/api/v1/transactions?size=100", unavailable.token())).get("items"))
        .isEmpty();

    // Audit rows and outbox payloads of the test's own transfers: no description, no reason.
    for (UUID id : own) {
      for (Map<String, Object> audit : reviewAuditsOf(id)) {
        assertThat(String.valueOf(audit.get("new_value")) + audit.get("old_value"))
            .doesNotContain("note-7e2a")
            .doesNotContain(REASON_MARK);
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE new_value::text LIKE ?"
                    + " OR old_value::text LIKE ? OR new_value::text LIKE ?"
                    + " OR old_value::text LIKE ?",
                Integer.class,
                "%" + REASON_MARK + "%",
                "%" + REASON_MARK + "%",
                "%note-7e2a%",
                "%note-7e2a%"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.notifications WHERE payload::text LIKE ?"
                    + " OR payload::text LIKE ?",
                Integer.class,
                "%" + REASON_MARK + "%",
                "%note-7e2a%"))
        .isZero();
    assertThat(delivered).hasSizeGreaterThanOrEqualTo(4);
    for (EmailMessage message : delivered) {
      assertThat(message.subject() + message.body())
          .doesNotContain("note-7e2a")
          .doesNotContain(REASON_MARK)
          .doesNotContain("Jaidee")
          .doesNotContain("Somchai")
          .doesNotContain(numberOf(sender))
          .doesNotContain(numberOf(recipient))
          .doesNotContain(numberOf(unavailable))
          .doesNotContainIgnoringCase("score");
    }

    // Leave nothing held by this test.
    for (UUID id : List.of(reminded, failing)) {
      overdue(id, 60);
    }
  }
}
