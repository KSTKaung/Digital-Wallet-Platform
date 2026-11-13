package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.support.OutboxRows.Row;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * The compliance officer's transfer review over real HTTP (documentations/02 §2.7; doc 06
 * MVP-ADR-12, MVP-ADR-24 "Review", review cases 1, 2, 3 and 6, "Rejection categories (Q10)",
 * "Review queue (Q12)"; doc 08 §4C.4 criteria 11, 15, 19, 20; §5 notes): roles, the reviewer who is
 * a party, the queue, the detail, approve (captured or wallet unavailable), reject, late actions
 * and the reviewer's reason kept staff-only. Races are in {@link TransferStaffReviewRaceIT},
 * failures at each write point in {@link TransferFailureInjectionIT}.
 *
 * <p>Every assertion reads only this test's own rows.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferStaffReviewIT extends TransferReviewFlowSupport {

  /** A reviewer's reason no template, log or audit text can contain by chance. */
  private static final String REASON = "Matches a known mule pattern reason-c41d";

  private static final String NOTE = "note-5b9a";

  /** As the support's, with a distinctive description. */
  @Override
  UUID held(Account sender, Account recipient) throws Exception {
    MockHttpServletResponse response =
        postTransfer(
            transferBody(numberOf(recipient), HOLD, NOTE),
            sender.token(),
            UUID.randomUUID(),
            randomIp());
    assertThat(response.getStatus()).as("hold").isEqualTo(201);
    return transferId(response);
  }

  private String status(UUID id) {
    return (String) transferRow(id).get("status");
  }

  private List<String> emailKinds(Account account) {
    return transferEmails(account).stream().map(Row::kind).toList();
  }

  private static String masked(String walletNumber) {
    return "*".repeat(walletNumber.length() - 4)
        + walletNumber.substring(walletNumber.length() - 4);
  }

  private LocalDate limitDate(UUID id) {
    return ((java.sql.Date) transferRow(id).get("limit_date")).toLocalDate();
  }

  private void assertUsage(Account sender, UUID id, String used, String reserved) {
    assertThat(transferUsage(sender, limitDate(id)))
        .singleElement()
        .satisfies(
            usage -> {
              assertThat((BigDecimal) usage.get("used")).isEqualByComparingTo(used);
              assertThat((BigDecimal) usage.get("reserved")).isEqualByComparingTo(reserved);
            });
  }

  /** A token with {@code role} for an existing user, with a live session of its own. */
  private String tokenAs(UUID userId, Role role) throws Exception {
    UUID session = UUID.randomUUID();
    Instant now = clock.instant();
    insert(
        "auth.sessions",
        row("id", session, "user_id", userId, "last_seen_at", now.atOffset(ZoneOffset.UTC)));
    return issuer.issue(userId, role, session, now, now.plus(Duration.ofMinutes(15)));
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

  private void assertNothingDecided(UUID id, Account sender, Account recipient) {
    assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(reviewRows(id)).isEmpty();
    assertThat(reviewAuditsOf(id)).isEmpty();
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD");
    assertThat(transferEmails(recipient)).isEmpty();
  }

  // Roles ---------------------------------------------------------------------------------------

  @Test
  void onlyAComplianceOfficerMayUseTheReviewEndpoints() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);

    for (String token : List.of(customer().token(), sender.token(), account(Role.ADMIN).token())) {
      for (MockHttpServletResponse response :
          List.of(
              getAs(REVIEWS, token),
              getAs(REVIEWS + "/" + id, token),
              send(post(REVIEWS + "/" + id + "/approve"), token),
              send(
                  post(REVIEWS + "/" + id + "/reject")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"reason\": "),
                  token))) {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
      }
    }
    MockHttpServletResponse reference = getAs("/api/v1/no-such-thing", null);
    for (MockHttpServletResponse response :
        List.of(
            getAs(REVIEWS, null),
            getAs(REVIEWS + "/" + id, null),
            send(post(REVIEWS + "/" + id + "/approve"), null))) {
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(stable(response)).isEqualTo(stable(reference));
    }
    assertNothingDecided(id, sender, recipient);
    assertThat(getAs(REVIEWS + "/" + id, officer().token()).getStatus()).isEqualTo(200);
  }

  /**
   * MVP-ADR-24: neither the sender nor the recipient may review the transfer. Staff and customer
   * accounts are separate (MVP-ADR-12), so this simulates a token that would break that rule; the
   * answer is {@code 403}, as for a KYC reviewer's own application, and nothing is written.
   */
  @Test
  void theSenderOrTheRecipientAsReviewerIsRefusedAndNothingIsWritten() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);

    for (Account party : List.of(sender, recipient)) {
      Account asOfficer = new Account(party.id(), tokenAs(party.id(), Role.COMPLIANCE_OFFICER));
      for (MockHttpServletResponse response :
          List.of(approveReview(id, asOfficer), rejectReview(id, REASON, asOfficer))) {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
      }
    }
    // Also after the deadline: a party never expires it either.
    overdue(id, 60);
    Account asOfficer = new Account(sender.id(), tokenAs(sender.id(), Role.COMPLIANCE_OFFICER));
    assertThat(approveReview(id, asOfficer).getStatus()).isEqualTo(403);

    assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    assertThat(reviewRows(id)).isEmpty();
    assertThat(reviewAuditsOf(id)).isEmpty();
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD");
    // Leave nothing overdue held by this test.
    assertThat(approveReview(id, officer()).getStatus()).isEqualTo(409);
    assertThat(status(id)).isEqualTo("EXPIRED");
  }

  // Queue ---------------------------------------------------------------------------------------

  @Test
  void theQueueListsOnlyHeldTransfersSoonestDeadlineFirstThenById() throws Exception {
    Account sender = funded("10000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID a = held(sender, recipient);
    UUID b = held(sender, recipient);
    UUID c = held(sender, recipient);
    UUID cancelled = held(sender, recipient);
    assertThat(cancel(cancelled, sender).getStatus()).isEqualTo(200);
    MockHttpServletResponse allowed = transfer(sender, recipient, ALLOW);
    assertThat(allowed.getStatus()).isEqualTo(201);
    UUID completed = transferId(allowed);
    // Far-future deadlines unique to this test: a and b share one, c comes before them.
    OffsetDateTime shared =
        OffsetDateTime.of(2100, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
            .plusSeconds(ThreadLocalRandom.current().nextInt(1_000_000));
    deadline(a, shared);
    deadline(b, shared);
    deadline(c, shared.minusSeconds(1));

    List<UUID> own = new ArrayList<>();
    List<UUID> all = new ArrayList<>();
    long total = -1;
    for (int page = 0; ; page++) {
      MockHttpServletResponse response =
          getAs(REVIEWS + "?page=" + page + "&size=100", reviewer.token());
      assertThat(response.getStatus()).isEqualTo(200);
      JsonNode body = body(response);
      total = body.get("page").get("totalElements").asLong();
      if (body.get("items").isEmpty()) {
        break;
      }
      for (JsonNode item : body.get("items")) {
        UUID id = UUID.fromString(item.get("transferId").asString());
        all.add(id);
        if (List.of(a, b, c, cancelled, completed).contains(id)) {
          own.add(id);
        }
      }
    }
    List<UUID> tie =
        jdbc.queryForList(
            "SELECT id FROM transfer.transfers WHERE id IN (?, ?) ORDER BY id", UUID.class, a, b);
    assertThat(own).containsExactly(c, tie.get(0), tie.get(1));
    assertThat(all).doesNotHaveDuplicates();
    assertThat(all).hasSize((int) total);
    assertThat(total)
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT count(*) FROM transfer.transfers WHERE status = 'PENDING_REVIEW'",
                Long.class));
    // The whole order, checked against the database's.
    List<UUID> firstTwo =
        jdbc.queryForList(
            "SELECT id FROM transfer.transfers WHERE status = 'PENDING_REVIEW'"
                + " ORDER BY review_expires_at, id LIMIT 2",
            UUID.class);
    assertThat(itemIds(getAs(REVIEWS + "?page=0&size=1", reviewer.token())))
        .containsExactly(firstTwo.get(0));
    assertThat(itemIds(getAs(REVIEWS + "?page=1&size=1", reviewer.token())))
        .containsExactly(firstTwo.get(1));

    JsonNode item = itemOf(reviewer, c);
    assertThat(item.get("amount").decimalValue()).isEqualByComparingTo(HOLD);
    assertThat(item.get("currency").asString()).isEqualTo("THB");
    assertThat(item.get("score").asInt()).isEqualTo(70);
    assertThat(item.toString()).doesNotContain(NOTE, numberOf(recipient), "Somchai");
  }

  @ParameterizedTest
  @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=x"})
  void anInvalidPageOrSizeIs400(String query) throws Exception {
    MockHttpServletResponse response = getAs(REVIEWS + "?" + query, officer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
  }

  private void deadline(UUID id, OffsetDateTime deadline) throws Exception {
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET review_expires_at = ? WHERE id = ?",
                deadline,
                id));
  }

  private List<UUID> itemIds(MockHttpServletResponse response) throws Exception {
    List<UUID> ids = new ArrayList<>();
    body(response)
        .get("items")
        .forEach(i -> ids.add(UUID.fromString(i.get("transferId").asString())));
    return ids;
  }

  private JsonNode itemOf(Account reviewer, UUID id) throws Exception {
    for (int page = 0; ; page++) {
      JsonNode items =
          body(getAs(REVIEWS + "?page=" + page + "&size=100", reviewer.token())).get("items");
      if (items.isEmpty()) {
        throw new AssertionError("not in the queue: " + id);
      }
      for (JsonNode item : items) {
        if (item.get("transferId").asString().equals(id.toString())) {
          return item;
        }
      }
    }
  }

  // Detail --------------------------------------------------------------------------------------

  @Test
  void theDetailShowsScoreRulesNamesAndMaskedNumbersButNoDescription() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    Map<String, Object> row = transferRow(id);

    MockHttpServletResponse response = reviewDetail(id, officer());

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode detail = body(response);
    assertThat(detail.get("transferId").asString()).isEqualTo(id.toString());
    assertThat(detail.get("reference").asString()).isEqualTo(row.get("reference"));
    assertThat(detail.get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertThat(detail.get("amount").decimalValue()).isEqualByComparingTo(HOLD);
    assertThat(detail.get("heldAt").isNull()).isFalse();
    assertThat(Instant.parse(detail.get("reviewExpiresAt").asString()))
        .isEqualTo(
            ((OffsetDateTime)
                    jdbc.queryForObject(
                        "SELECT review_expires_at FROM transfer.transfers WHERE id = ?",
                        OffsetDateTime.class,
                        id))
                .toInstant());
    assertThat(detail.get("decidedAt").isNull()).isTrue();
    JsonNode risk = detail.get("risk");
    assertThat(risk.get("score").asInt()).isEqualTo(70);
    assertThat(risk.get("decision").asString()).isEqualTo("MANUAL_REVIEW");
    assertThat(risk.get("rules").toString())
        .isEqualTo(
            "[{\"code\":\"TRANSFER_AMOUNT\",\"weight\":67},"
                + "{\"code\":\"NEW_SENDER_WALLET\",\"weight\":2},"
                + "{\"code\":\"NEW_RECIPIENT\",\"weight\":1}]");
    for (String party : List.of("sender", "recipient")) {
      Account account = party.equals("sender") ? sender : recipient;
      JsonNode node = detail.get(party);
      Map<String, Object> names =
          jdbc.queryForMap(
              "SELECT first_name, last_name FROM usr.customers WHERE user_id = ?", account.id());
      assertThat(node.get("customerId").asString()).isEqualTo(account.id().toString());
      assertThat(node.get("firstName").asString()).isEqualTo(names.get("first_name"));
      assertThat(node.get("lastName").asString()).isEqualTo(names.get("last_name"));
      assertThat(node.get("maskedWalletNumber").asString()).isEqualTo(masked(numberOf(account)));
    }
    assertThat(detail.get("history")).isEmpty();
    String json = response.getContentAsString();
    assertThat(json)
        .doesNotContain(NOTE, numberOf(sender), numberOf(recipient), "description")
        .doesNotContain(walletId(sender).toString());
  }

  @Test
  void aMissingTransferAndOneNeverHeldAreTheIdentical404() throws Exception {
    Account sender = funded("5000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    MockHttpServletResponse block = transfer(sender, recipient, BLOCK);
    assertThat(block.getStatus()).isEqualTo(403);
    // After the block: a completed transfer to this recipient removes the new-recipient weight.
    UUID completed = transferId(transfer(sender, recipient, ALLOW));
    UUID blocked =
        jdbc.queryForObject(
            "SELECT id FROM transfer.transfers WHERE sender_customer_id = ? AND status = 'BLOCKED'",
            UUID.class,
            sender.id());
    MockHttpServletResponse reference = reviewDetail(UUID.randomUUID(), reviewer);
    assertThat(reference.getStatus()).isEqualTo(404);

    for (UUID id : List.of(completed, blocked)) {
      for (MockHttpServletResponse response :
          List.of(
              reviewDetail(id, reviewer),
              approveReview(id, reviewer),
              rejectReview(id, REASON, reviewer))) {
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(stable(response)).isEqualTo(stable(reference));
      }
    }
    assertThat(approveReview(UUID.randomUUID(), reviewer).getStatus()).isEqualTo(404);
    assertThat(getAs(REVIEWS + "/not-a-uuid", reviewer.token()).getStatus()).isEqualTo(400);
    assertThat(reviewRows(completed)).isEmpty();
    assertThat(reviewRows(blocked)).isEmpty();
  }

  // Approve: case 1 -----------------------------------------------------------------------------

  @Test
  void anApprovalCompletesAtomicallyFromTheHold() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    String reference = (String) transferRow(id).get("reference");
    assertUsage(sender, id, "0", "1300.00");

    MockHttpServletResponse response = approveReview(id, reviewer);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    JsonNode detail = body(response);
    assertThat(detail.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.get("ledgerStatus").asString()).isEqualTo("POSTED");
    assertThat(detail.get("decidedAt").isNull()).isFalse();
    assertThat(detail.get("history")).hasSize(1);
    JsonNode history = detail.get("history").get(0);
    assertThat(history.get("action").asString()).isEqualTo("APPROVE");
    assertThat(history.get("decidedBy").asString()).isEqualTo("STAFF");
    assertThat(history.get("actorUserId").asString()).isEqualTo(reviewer.id().toString());

    assertThat(transferRow(id))
        .containsEntry("status", "COMPLETED")
        .containsEntry("ledger_status", "POSTED")
        .containsEntry("rejection_reason_category", null);
    assertBalances(sender, "700.00", "0.00");
    assertBalances(recipient, "1300.00", "0.00");
    // The stored day's reservation became used.
    assertUsage(sender, id, "1300.00", "0");
    assertThat(ledgerTransactions(reference)).isOne();
    assertBalancedPosting(reference, HOLD);
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "APPROVE")
                    .containsEntry("decided_by", "STAFF")
                    .containsEntry("requested_action", "APPROVE")
                    .containsEntry("actor_user_id", reviewer.id())
                    .containsEntry("system_reason", null)
                    .containsEntry("reason_category", null)
                    .containsEntry("reason", null));
    Map<String, Object> audit = reviewAuditsOf(id).getFirst();
    assertThat(reviewAuditsOf(id)).hasSize(1);
    assertThat(audit)
        .containsEntry("actor_id", reviewer.id())
        .containsEntry("actor_type", "STAFF")
        .containsEntry("result", "SUCCESS");
    assertThat((String) audit.get("new_value"))
        .contains("\"action\": \"APPROVE\"")
        .contains("\"decidedBy\": \"STAFF\"")
        .contains("\"requestedAction\": \"APPROVE\"")
        .contains("\"ledgerTransactionId\"")
        .doesNotContain(NOTE);
    // Q9: TRANSFER_SENT to the sender and TRANSFER_RECEIVED to the recipient, after commit.
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_SENT");
    assertThat(emailKinds(recipient)).containsExactly("TRANSFER_RECEIVED");
    assertThat(transferEmails(recipient).getFirst().values())
        .isEqualTo(emailValues("1300.00", reference));

    // The customers now see the completed transfer; the recipient for the first time (Q8).
    JsonNode senderView = body(getAs(TRANSFERS + "/" + id, sender.token()));
    assertThat(senderView.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(senderView.get("ledgerStatus").asString()).isEqualTo("POSTED");
    assertThat(getAs(TRANSFERS + "/" + id, recipient.token()).getStatus()).isEqualTo(200);

    // A repeated approval or a rejection: 409, nothing more written.
    for (MockHttpServletResponse again :
        List.of(approveReview(id, reviewer), rejectReview(id, REASON, officer()))) {
      assertThat(again.getStatus()).isEqualTo(409);
      assertThat(errorCode(again)).isEqualTo("INVALID_TRANSFER_STATE");
    }
    assertThat(reviewRows(id)).hasSize(1);
    assertThat(reviewAuditsOf(id)).hasSize(1);
    assertThat(ledgerTransactions(reference)).isOne();
    assertBalances(recipient, "1300.00", "0.00");
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_SENT");
  }

  // Approve: case 3 -----------------------------------------------------------------------------

  /**
   * MVP-ADR-24: a frozen or closed wallet at approval rejects and releases (sender checked first).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "sender:FROZEN:SENDER_WALLET_FROZEN",
        "sender:CLOSED:SENDER_WALLET_CLOSED",
        "recipient:FROZEN:RECIPIENT_WALLET_FROZEN",
        "recipient:CLOSED:RECIPIENT_WALLET_CLOSED",
        "both:FROZEN:SENDER_WALLET_FROZEN"
      })
  void anApprovalWithAnUnavailableWalletRejectsAndReleasesTheHold(String scenario)
      throws Exception {
    String[] parts = scenario.split(":");
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    String reference = (String) transferRow(id).get("reference");
    if (!parts[0].equals("recipient")) {
      setWalletStatus(sender, parts[1]);
    }
    if (!parts[0].equals("sender")) {
      setWalletStatus(recipient, parts[1]);
    }

    MockHttpServletResponse response = approveReview(id, reviewer);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    JsonNode detail = body(response);
    assertThat(detail.get("status").asString()).isEqualTo("REJECTED");
    assertThat(detail.get("rejectionReasonCategory").asString()).isEqualTo("WALLET_UNAVAILABLE");
    assertThat(detail.get("ledgerStatus").isNull()).isTrue();
    assertThat(transferRow(id))
        .containsEntry("status", "REJECTED")
        .containsEntry("rejection_reason_category", "WALLET_UNAVAILABLE")
        .containsEntry("ledger_status", null);
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertUsage(sender, id, "0", "0");
    assertThat(ledgerTransactions(reference)).isZero();
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "REJECT")
                    .containsEntry("decided_by", "SYSTEM")
                    .containsEntry("requested_action", "APPROVE")
                    .containsEntry("actor_user_id", reviewer.id())
                    .containsEntry("system_reason", parts[2])
                    .containsEntry("reason_category", null)
                    .containsEntry("reason", null));
    assertThat((String) reviewAuditsOf(id).getFirst().get("new_value"))
        .contains("\"decidedBy\": \"SYSTEM\"")
        .contains("\"systemReason\": \"" + parts[2] + "\"")
        .contains("\"rejectionReasonCategory\": \"WALLET_UNAVAILABLE\"");
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_REJECTED");
    assertThat(transferEmails(recipient)).isEmpty();
    // The customer sees only the category, never whose wallet.
    String senderView = getAs(TRANSFERS + "/" + id, sender.token()).getContentAsString();
    assertThat(senderView).contains("\"rejectionReason\":\"WALLET_UNAVAILABLE\"");
    assertThat(senderView).doesNotContain("SENDER_WALLET", "RECIPIENT_WALLET");
    assertThat(getAs(TRANSFERS + "/" + id, recipient.token()).getStatus()).isEqualTo(404);
  }

  // Reject: case 2 ------------------------------------------------------------------------------

  @Test
  void aRejectionReleasesTheHoldAndKeepsTheReasonStaffOnly(CapturedOutput output) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    String reference = (String) transferRow(id).get("reference");
    int start = output.getAll().length();

    MockHttpServletResponse response = rejectReview(id, "  " + REASON + "  ", reviewer);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    JsonNode detail = body(response);
    assertThat(detail.get("status").asString()).isEqualTo("REJECTED");
    assertThat(detail.get("rejectionReasonCategory").asString()).isEqualTo("REVIEW_DECLINED");
    // Staff see their own text, stored normalised (stripped).
    JsonNode history = detail.get("history").get(0);
    assertThat(history.get("reason").asString()).isEqualTo(REASON);
    assertThat(history.get("reasonCategory").asString()).isEqualTo("STAFF_REJECTION");
    assertThat(transferRow(id))
        .containsEntry("status", "REJECTED")
        .containsEntry("rejection_reason_category", "REVIEW_DECLINED");
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertUsage(sender, id, "0", "0");
    assertThat(ledgerTransactions(reference)).isZero();
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "REJECT")
                    .containsEntry("decided_by", "STAFF")
                    .containsEntry("requested_action", "REJECT")
                    .containsEntry("actor_user_id", reviewer.id())
                    .containsEntry("reason_category", "STAFF_REJECTION")
                    .containsEntry("reason", REASON)
                    .containsEntry("system_reason", null));
    Map<String, Object> audit = reviewAuditsOf(id).getFirst();
    assertThat(audit).containsEntry("actor_type", "STAFF");
    assertThat((String) audit.get("new_value"))
        .contains("\"reasonCategory\": \"STAFF_REJECTION\"")
        .contains("\"rejectionReasonCategory\": \"REVIEW_DECLINED\"")
        .doesNotContain("reason-c41d");
    // The reason is in no audit row at all, no customer response, no email and no log line.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE new_value::text LIKE ?"
                    + " OR old_value::text LIKE ?",
                Integer.class,
                "%reason-c41d%",
                "%reason-c41d%"))
        .isZero();
    MockHttpServletResponse senderView = getAs(TRANSFERS + "/" + id, sender.token());
    assertThat(senderView.getContentAsString())
        .contains("\"rejectionReason\":\"REVIEW_DECLINED\"")
        .doesNotContain("reason-c41d", "STAFF_REJECTION");
    assertThat(getAs(TRANSFERS, sender.token()).getContentAsString()).doesNotContain("reason-c41d");
    assertThat(getAs(TRANSFERS + "/" + id, recipient.token()).getStatus()).isEqualTo(404);
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_REJECTED");
    assertThat(transferEmails(recipient)).isEmpty();
    Row email = transferEmails(sender).get(1);
    assertThat(email.values()).isEqualTo(emailValues("1300.00", reference));
    EmailMessage message = delivered(sender, email);
    assertThat(message.subject()).isEqualTo("Your transfer was not completed");
    assertThat(message.body()).contains("1,300.00 THB").doesNotContain("reason-c41d", NOTE);
    assertThat(output.getAll().substring(start)).doesNotContain("reason-c41d");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"reason\": null}",
        "{\"reason\": \"\"}",
        "{\"reason\": \"   \"}",
        "{\"reason\": \"!!!\"}",
        "{\"reason\": \"zero\\u200bwidth\"}",
        "{\"reason\": \"bell\\u0007\"}"
      })
  void anInvalidReasonBeforeTheDeadlineIs400AndWritesNothing(String json) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);

    MockHttpServletResponse response =
        send(
            post(REVIEWS + "/" + id + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json),
            officer().token());

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(response).get("message").asString()).startsWith("reason");
    assertNothingDecided(id, sender, recipient);
  }

  @Test
  void aReasonOfMoreThan500CharactersIs400And500AreAccepted() throws Exception {
    Account sender = funded("3000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);

    MockHttpServletResponse tooLong = rejectReview(id, "a".repeat(501), reviewer);
    assertThat(tooLong.getStatus()).isEqualTo(400);
    assertThat(body(tooLong).get("message").asString()).startsWith("reason");
    assertThat(tooLong.getContentAsString()).doesNotContain("aaaaaaaaaa");
    assertThat(status(id)).isEqualTo("PENDING_REVIEW");

    assertThat(rejectReview(id, "a".repeat(500), reviewer).getStatus()).isEqualTo(200);
    assertThat(status(id)).isEqualTo("REJECTED");
  }

  // Late actions: case 6 ------------------------------------------------------------------------

  /** MVP-ADR-24: an action after the deadline expires the transfer in its transaction; 409. */
  @ParameterizedTest
  @ValueSource(strings = {"approve", "reject", "reject-invalid-reason", "reject-missing-reason"})
  void aLateActionExpiresTheTransferAndIs409(String action) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    overdue(id, 60);

    MockHttpServletResponse response =
        switch (action) {
          case "approve" -> approveReview(id, reviewer);
          case "reject" -> rejectReview(id, REASON, reviewer);
          case "reject-invalid-reason" -> rejectReview(id, "!!!", reviewer);
          default ->
              send(
                  post(REVIEWS + "/" + id + "/reject")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"),
                  reviewer.token());
        };

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
    assertThat(errorCode(response)).isEqualTo("INVALID_TRANSFER_STATE");
    assertThat(status(id)).isEqualTo("EXPIRED");
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertUsage(sender, id, "0", "0");
    assertThat(ledgerTransactions((String) transferRow(id).get("reference"))).isZero();
    // The reviewer's text and category are dropped (ck_risk_reviews_staff_rejection holds).
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "EXPIRE")
                    .containsEntry("decided_by", "SYSTEM")
                    .containsEntry(
                        "requested_action", action.equals("approve") ? "APPROVE" : "REJECT")
                    .containsEntry("actor_user_id", reviewer.id())
                    .containsEntry("system_reason", "REVIEW_DEADLINE_PASSED")
                    .containsEntry("reason_category", null)
                    .containsEntry("reason", null));
    Map<String, Object> audit = reviewAuditsOf(id).getFirst();
    assertThat(reviewAuditsOf(id)).hasSize(1);
    assertThat(audit).containsEntry("actor_type", "STAFF").containsEntry("actor_id", reviewer.id());
    assertThat((String) audit.get("new_value"))
        .contains("\"action\": \"EXPIRE\"")
        .contains("\"systemReason\": \"REVIEW_DEADLINE_PASSED\"")
        .doesNotContain("reason-c41d");
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_EXPIRED");
    assertThat(transferEmails(recipient)).isEmpty();
    // The committed expiry stands: a second action is 409 and writes nothing.
    assertThat(approveReview(id, reviewer).getStatus()).isEqualTo(409);
    assertThat(reviewRows(id)).hasSize(1);
  }

  // Reject without a body (security L1) ---------------------------------------------------------

  /** No body at all, with or without a JSON content type. */
  private MockHttpServletResponse rejectWithoutBody(UUID id, Account reviewer, boolean json)
      throws Exception {
    var request = post(REVIEWS + "/" + id + "/reject");
    if (json) {
      request.contentType(MediaType.APPLICATION_JSON);
    }
    return send(request, reviewer.token());
  }

  /** Before the deadline a missing body is a missing reason: 400 naming it, nothing written. */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aRejectWithoutABodyBeforeTheDeadlineIs400AndWritesNothing(boolean json) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);

    MockHttpServletResponse response = rejectWithoutBody(id, officer(), json);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(response).get("message").asString()).startsWith("reason");
    assertNothingDecided(id, sender, recipient);
  }

  /** Doc 08 §5: the deadline before the reason, also when there is no body at all (case 6). */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aRejectWithoutABodyAfterTheDeadlineExpiresTheTransferAndIs409(boolean json)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    overdue(id, 60);

    MockHttpServletResponse response = rejectWithoutBody(id, reviewer, json);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
    assertThat(errorCode(response)).isEqualTo("INVALID_TRANSFER_STATE");
    assertThat(status(id)).isEqualTo("EXPIRED");
    assertBalances(sender, "2000.00", "0.00");
    assertUsage(sender, id, "0", "0");
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "EXPIRE")
                    .containsEntry("requested_action", "REJECT")
                    .containsEntry("actor_user_id", reviewer.id())
                    .containsEntry("reason", null)
                    .containsEntry("reason_category", null));
    assertThat(reviewAuditsOf(id)).hasSize(1);
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_EXPIRED");
  }

  /**
   * Protocol errors stay protocol errors, before anything is locked, also after the deadline: an
   * unreadable JSON body is 400 and another media type 415. They expire nothing; a valid action (or
   * the job) decides the transfer afterwards.
   */
  @Test
  void anUnreadableBodyOrAnotherMediaTypeIsAProtocolErrorAndExpiresNothing() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    overdue(id, 60);

    MockHttpServletResponse unreadable =
        send(
            post(REVIEWS + "/" + id + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": "),
            reviewer.token());
    MockHttpServletResponse otherType =
        send(
            post(REVIEWS + "/" + id + "/reject")
                .contentType(MediaType.TEXT_PLAIN)
                .content("Unusual activity"),
            reviewer.token());

    assertThat(unreadable.getStatus()).isEqualTo(400);
    assertThat(errorCode(unreadable)).isEqualTo("VALIDATION_ERROR");
    assertThat(otherType.getStatus()).isEqualTo(415);
    assertThat(errorCode(otherType)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    assertThat(reviewRows(id)).isEmpty();
    assertThat(reviewAuditsOf(id)).isEmpty();

    // A valid late action then expires it (case 6).
    assertThat(rejectReview(id, REASON, reviewer).getStatus()).isEqualTo(409);
    assertThat(status(id)).isEqualTo("EXPIRED");
  }

  /** Q15: review actions take no Idempotency-Key and are not money POSTs. */
  @Test
  void reviewActionsNeedNoIdempotencyKey() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);

    MockHttpServletResponse response =
        send(
            post(REVIEWS + "/" + id + "/approve").header(KEY_HEADER, "not-a-uuid"),
            officer().token());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(status(id)).isEqualTo("COMPLETED");
  }
}
