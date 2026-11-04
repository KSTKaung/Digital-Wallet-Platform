package com.portfolio.wallet.admin.web;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.wallet.domain.WalletNumber;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Staff KYC review end to end (documentations/02 §2.7; doc 06 MVP-ADR-10 amendment; doc 08 §4B.4 AC
 * 2, 3, 11): approval creates the wallet and its ledger account in one transaction, rejection with
 * a reason, re-application, the list, and what each response and audit row may contain.
 */
class KycReviewFlowIT extends AdminFlowSupport {

  // Approve ------------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({"BASIC, BASIC", "FULL, VERIFIED"})
  void approvalCreatesTheWalletAndItsLedgerAccountAndLocksTheProfile(String level, String tier)
      throws Exception {
    Account customer = customer();
    Account officer = officer();
    UUID application = pending(customer, level);

    MockHttpServletResponse response = approve(application, officer.token());

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = body(response);
    assertThat(body.get("status").asString()).isEqualTo("APPROVED");
    assertThat(body.get("reviewerId").asString()).isEqualTo(officer.id().toString());
    assertThat(body.get("documentReference").asString()).isEqualTo("***4567");
    assertThat(body.get("firstName").asString()).isEqualTo(FIRST_NAME);
    assertThat(body.get("lastName").asString()).isEqualTo(LAST_NAME);
    assertThat(body.get("decidedAt").isNull()).isFalse();
    assertThat(response.getContentAsString()).doesNotContain(REFERENCE);

    List<Map<String, Object>> wallets = walletsOf(customer.id());
    assertThat(wallets).hasSize(1);
    Map<String, Object> wallet = wallets.getFirst();
    assertThat(wallet.get("status")).isEqualTo("ACTIVE");
    assertThat(wallet.get("tier")).isEqualTo(tier);
    assertThat(wallet.get("currency")).isEqualTo("THB");
    assertThat((BigDecimal) wallet.get("available_balance")).isEqualByComparingTo("0");
    assertThat((BigDecimal) wallet.get("reserved_balance")).isEqualByComparingTo("0");
    assertThat(wallet.get("version")).isEqualTo(0L);
    String number = (String) wallet.get("wallet_number");
    assertThat(WalletNumber.parse(number).value()).isEqualTo(number);

    UUID walletId = (UUID) wallet.get("id");
    List<Map<String, Object>> accounts = ledgerAccountsOf(walletId);
    assertThat(accounts).hasSize(1);
    assertThat(accounts.getFirst())
        .containsEntry("account_type", "CUSTOMER")
        .containsEntry("status", "ACTIVE")
        .containsEntry("currency", "THB")
        .containsEntry("code", null);

    assertThat(profile(customer.id()))
        .containsEntry("first_name", FIRST_NAME)
        .containsEntry("last_name", LAST_NAME);
    MockHttpServletResponse locked =
        putAs(PROFILE, Map.of("dateOfBirth", "1991-01-01", "countryCode", "TH"), customer.token());
    assertThat(locked.getStatus()).isEqualTo(409);
    assertThat(errorCode(locked)).isEqualTo("INVALID_KYC_STATE");

    JsonNode status = body(getAs(STATUS, customer.token()));
    assertThat(status.get("status").asString()).isEqualTo("APPROVED");
    assertThat(status.get("walletCreated").asBoolean()).isTrue();
  }

  @Test
  void approvalAndWalletCreationAreAuditedTogetherWithoutKycContent() throws Exception {
    Account customer = customer();
    Account officer = officer();
    UUID application = pending(customer);
    String correlation = UUID.randomUUID().toString();

    MockHttpServletResponse response =
        sendWithCorrelation(
            post(REVIEW + "/" + application + "/approve"), officer.token(), correlation);
    assertThat(response.getStatus()).isEqualTo(200);

    List<Map<String, Object>> decision = auditRows(application.toString());
    Map<String, Object> approval =
        decision.stream()
            .filter(r -> "KYC_APPLICATION_APPROVE".equals(r.get("action")))
            .findFirst()
            .orElseThrow();
    assertThat(approval)
        .containsEntry("actor_id", officer.id())
        .containsEntry("actor_type", "STAFF")
        .containsEntry("resource", "KYC_APPLICATION")
        .containsEntry("result", "SUCCESS")
        .containsEntry("correlation_id", correlation);
    UUID walletId = (UUID) walletsOf(customer.id()).getFirst().get("id");
    List<Map<String, Object>> created = auditRows(walletId.toString());
    assertThat(created).hasSize(1);
    assertThat(created.getFirst())
        .containsEntry("action", "WALLET_CREATE")
        .containsEntry("actor_id", officer.id())
        .containsEntry("actor_type", "STAFF")
        .containsEntry("correlation_id", correlation);
    String number = (String) walletsOf(customer.id()).getFirst().get("wallet_number");
    for (Map<String, Object> row : List.of(approval, created.getFirst())) {
      assertThat(String.valueOf(row.get("old_value")) + row.get("new_value"))
          .doesNotContain(REFERENCE, FIRST_NAME, LAST_NAME, "1990-05-17", number);
    }
  }

  @Test
  void theCustomerIsEmailedAfterTheCommitWithoutKycContent() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);

    assertThat(approve(application, officer().token()).getStatus()).isEqualTo(200);

    EmailMessage message = awaitMail(customer.email(), EmailKind.KYC_APPROVED);
    assertThat(message.subject() + message.body())
        .doesNotContain(REFERENCE, "4567", FIRST_NAME, LAST_NAME);
    // K2, MVP-ADR-25: the outbox row records the decision's own time, and the email states it in
    // the business zone.
    OffsetDateTime decidedAt =
        jdbc.queryForObject(
            "SELECT decided_at FROM usr.kyc_applications WHERE id = ?",
            OffsetDateTime.class,
            application);
    assertThat(outboxRows(customer.id()))
        .singleElement()
        .satisfies(
            row ->
                assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant())
                    .isEqualTo(decidedAt.toInstant()));
    assertThat(message.body())
        .contains(
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm:ss", Locale.ENGLISH)
                    .format(decidedAt.atZoneSameInstant(ZoneId.of("Asia/Bangkok")))
                + " (Asia/Bangkok time)");
  }

  /** K4: the reviewer sees the date of birth and country copied at submit, not today's profile. */
  @Test
  void theReviewerSeesTheSnapshotTakenAtSubmitNotTheCurrentProfile() throws Exception {
    Account customer = customer();
    String officer = officer().token();
    UUID rejected = pending(customer);
    assertThat(reject(rejected, "Photo unreadable", officer).getStatus()).isEqualTo(200);
    // The rejection unlocks the profile; the customer corrects it and applies again.
    assertThat(
            putAs(
                    PROFILE,
                    Map.of("dateOfBirth", "1985-02-03", "countryCode", "GB"),
                    customer.token())
                .getStatus())
        .isEqualTo(200);
    UUID next = draft(customer, "BASIC");
    assertThat(postAs(APPLICATIONS + "/" + next + "/submit", null, customer.token()).getStatus())
        .isEqualTo(200);

    JsonNode past = body(getAs(REVIEW + "/" + rejected, officer));
    JsonNode current = body(getAs(REVIEW + "/" + next, officer));

    assertThat(past.get("dateOfBirth").asString()).isEqualTo("1990-05-17");
    assertThat(past.get("countryCode").asString()).isEqualTo("TH");
    assertThat(current.get("dateOfBirth").asString()).isEqualTo("1985-02-03");
    assertThat(current.get("countryCode").asString()).isEqualTo("GB");
  }

  /** K3, P2: the queue is ordered by submission time, oldest first, not by creation time. */
  @Test
  void theListIsOrderedBySubmissionTimeNotCreationTime() throws Exception {
    Account early = customer();
    Account late = customer();
    for (Account customer : List.of(early, late)) {
      assertThat(
              putAs(
                      PROFILE,
                      Map.of("dateOfBirth", "1990-05-17", "countryCode", "TH"),
                      customer.token())
                  .getStatus())
          .isEqualTo(200);
    }
    UUID createdFirst = draft(early, "BASIC");
    UUID createdSecond = draft(late, "BASIC");
    // Submitted in the other order.
    postAs(APPLICATIONS + "/" + createdSecond + "/submit", null, late.token());
    postAs(APPLICATIONS + "/" + createdFirst + "/submit", null, early.token());

    List<String> ids = allListedIds("status=PENDING_REVIEW", officer().token());

    // Oldest submission first (P2): the one submitted first, though created second, leads.
    assertThat(ids.subList(ids.size() - 2, ids.size()))
        .containsExactly(createdSecond.toString(), createdFirst.toString());
  }

  @Test
  void aSecondApprovalIsAConflictAndCreatesNothingMore() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    assertThat(approve(application, officer().token()).getStatus()).isEqualTo(200);

    MockHttpServletResponse again = approve(application, officer().token());

    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(errorCode(again)).isEqualTo("INVALID_KYC_STATE");
    assertThat(walletsOf(customer.id())).hasSize(1);
  }

  @Test
  void aCustomerWhoAlreadyHasAWalletIsAConflictAndNothingChanges() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    insert("wallet.wallets", with(validWallet(), "customer_id", customer.id()));

    MockHttpServletResponse response = approve(application, officer().token());

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(errorCode(response)).isEqualTo("INVALID_KYC_STATE");
    assertThat(application(application)).containsEntry("status", "PENDING_REVIEW");
    assertThat(profile(customer.id())).containsEntry("first_name", null);
    assertThat(walletsOf(customer.id())).hasSize(1);
    assertThat(auditRows(application.toString()))
        .noneMatch(r -> "KYC_APPLICATION_APPROVE".equals(r.get("action")));
    assertNoMailTo(customer.email());
  }

  @Test
  void anUnknownIdAndADraftGiveTheSame404AsEachOther() throws Exception {
    Account customer = customer();
    UUID draft = draft(customer, "BASIC");
    String token = officer().token();

    MockHttpServletResponse unknown = getAs(REVIEW + "/" + UUID.randomUUID(), token);
    MockHttpServletResponse ofDraft = getAs(REVIEW + "/" + draft, token);
    MockHttpServletResponse approveDraft = approve(draft, token);
    MockHttpServletResponse rejectDraft = reject(draft, "Unreadable", token);

    assertThat(unknown.getStatus()).isEqualTo(404);
    for (MockHttpServletResponse response : List.of(ofDraft, approveDraft, rejectDraft)) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(stable(response)).isEqualTo(stable(unknown));
    }
    assertThat(application(draft)).containsEntry("status", "DRAFT");
  }

  @Test
  void theDetailShowsTheProfileAndTheFullNameButAMaskedReference() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);

    MockHttpServletResponse response = getAs(REVIEW + "/" + application, officer().token());

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = body(response);
    assertThat(body.get("customerId").asString()).isEqualTo(customer.id().toString());
    assertThat(body.get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertThat(body.get("dateOfBirth").asString()).isEqualTo("1990-05-17");
    assertThat(body.get("countryCode").asString()).isEqualTo("TH");
    assertThat(body.get("firstName").asString()).isEqualTo(FIRST_NAME);
    assertThat(body.get("documentReference").asString()).isEqualTo("***4567");
    assertThat(response.getContentAsString()).doesNotContain(REFERENCE);
  }

  /** The staff detail view's fields; the decision responses reuse it and add nothing. */
  private static final List<String> DETAIL_FIELDS =
      List.of(
          "id",
          "customerId",
          "status",
          "verificationLevel",
          "documentType",
          "documentReference",
          "firstName",
          "lastName",
          "dateOfBirth",
          "countryCode",
          "reviewerId",
          "rejectionReason",
          "submittedAt",
          "decidedAt",
          "createdAt",
          "updatedAt");

  /**
   * Owner follow-up #5: approve and reject return exactly the detail view's fields, the reference
   * stays masked, and nothing else leaks: no wallet id, wallet number or ledger account id, and
   * nothing of the reviewer but the id the detail already shows.
   */
  @ParameterizedTest
  @ValueSource(strings = {"approve", "reject"})
  void aDecisionReturnsExactlyTheDetailViewFields(String decision) throws Exception {
    Account customer = customer();
    Account officer = officer();
    UUID application = pending(customer);
    MockHttpServletResponse detail = getAs(REVIEW + "/" + application, officer.token());

    MockHttpServletResponse response =
        "approve".equals(decision)
            ? approve(application, officer.token())
            : reject(application, "The document photo is unreadable", officer.token());

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = body(response);
    assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(DETAIL_FIELDS);
    assertThat(body(detail).propertyNames()).containsExactlyInAnyOrderElementsOf(DETAIL_FIELDS);
    assertThat(body.get("documentReference").asString()).isEqualTo("***4567");
    assertThat(body.get("reviewerId").asString()).isEqualTo(officer.id().toString());
    String content = response.getContentAsString();
    assertThat(content).doesNotContain(REFERENCE, officer.email(), "@example.test");
    for (Map<String, Object> wallet : walletsOf(customer.id())) {
      assertThat(content)
          .doesNotContain(wallet.get("id").toString(), (String) wallet.get("wallet_number"));
      for (Map<String, Object> account : ledgerAccountsOf((UUID) wallet.get("id"))) {
        assertThat(content).doesNotContain(account.get("id").toString());
      }
    }
  }

  // Reject -------------------------------------------------------------------------------------

  @Test
  void rejectionShowsTheReasonToTheCustomerButNotInAuditOrEmail() throws Exception {
    Account customer = customer();
    Account officer = officer();
    UUID application = pending(customer);
    String reason = "The document photo is unreadable";

    MockHttpServletResponse response = reject(application, reason, officer.token());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("status").asString()).isEqualTo("REJECTED");
    assertThat(application(application))
        .containsEntry("status", "REJECTED")
        .containsEntry("reviewer_id", officer.id())
        .containsEntry("rejection_reason", reason);
    JsonNode own = body(getAs(APPLICATIONS + "/" + application, customer.token()));
    assertThat(own.get("rejectionReason").asString()).isEqualTo(reason);
    assertThat(walletsOf(customer.id())).isEmpty();
    assertThat(profile(customer.id())).containsEntry("first_name", null);

    Map<String, Object> audit =
        auditRows(application.toString()).stream()
            .filter(r -> "KYC_APPLICATION_REJECT".equals(r.get("action")))
            .findFirst()
            .orElseThrow();
    assertThat(audit).containsEntry("actor_id", officer.id()).containsEntry("actor_type", "STAFF");
    assertThat(String.valueOf(audit.get("new_value")))
        .contains("REJECTED")
        .doesNotContain("unreadable");

    EmailMessage message = awaitMail(customer.email(), EmailKind.KYC_REJECTED);
    assertThat(message.body()).contains("Sign in to see the reason").doesNotContain("unreadable");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Reference AB1234567 is not valid",
        "ab-123 4567 is blurred",
        "A.B.1.2.3.4.5.6.7",
        "ＡＢ１２３４５６７"
      })
  void aReasonWithTheDocumentReferenceIsRefusedWithoutEchoingEither(String reason)
      throws Exception {
    Account customer = customer();
    UUID application = pending(customer);

    MockHttpServletResponse response = reject(application, reason, officer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(response).get("message").asString())
        .isEqualTo("reason: must not contain the document reference");
    assertThat(response.getContentAsString()).doesNotContain(REFERENCE, "blurred");
    assertThat(application(application)).containsEntry("status", "PENDING_REVIEW");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "bad\u0000reason", "bad\u0007reason"})
  void aBlankOrControlCharacterReasonIsAValidationErrorNamingTheField(String reason)
      throws Exception {
    UUID application = pending(customer());

    MockHttpServletResponse response = reject(application, reason, officer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(response).get("message").asString()).startsWith("reason: ");
    assertThat(application(application)).containsEntry("status", "PENDING_REVIEW");
  }

  @Test
  void aReasonOfAtMost500CharactersIsAccepted() throws Exception {
    String token = officer().token();
    UUID tooLong = pending(customer());
    UUID atLimit = pending(customer());

    MockHttpServletResponse refused = reject(tooLong, "x".repeat(501), token);
    MockHttpServletResponse accepted = reject(atLimit, "x".repeat(500), token);

    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(body(refused).get("message").asString()).startsWith("reason: ");
    assertThat(accepted.getStatus()).isEqualTo(200);
  }

  @Test
  void aMissingReasonIsAValidationError() throws Exception {
    UUID application = pending(customer());

    MockHttpServletResponse response =
        postAs(REVIEW + "/" + application + "/reject", Map.of(), officer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(body(response).get("message").asString()).startsWith("reason: ");
  }

  @Test
  void aDecidedApplicationCannotBeDecidedAgain() throws Exception {
    String token = officer().token();
    UUID approved = pending(customer());
    UUID rejected = pending(customer());
    assertThat(approve(approved, token).getStatus()).isEqualTo(200);
    assertThat(reject(rejected, "Unreadable", token).getStatus()).isEqualTo(200);

    for (MockHttpServletResponse response :
        List.of(
            reject(approved, "Unreadable", token),
            approve(rejected, token),
            reject(rejected, "Again", token))) {
      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(errorCode(response)).isEqualTo("INVALID_KYC_STATE");
    }
  }

  /**
   * MVP-ADR-10 "Tests": the reviewer cannot approve their own application. Staff and customer
   * accounts are separate (MVP-ADR-12), so this needs a validly signed compliance-officer token
   * whose subject is the applicant, as a mis-issued token or a future role mix would give. The
   * service refuses it before deciding, with the documented {@code 403} body, and nothing changes.
   */
  @Test
  void aReviewerCannotApproveOrRejectTheirOwnApplication() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    UUID session = UUID.randomUUID();
    Instant now = clock.instant();
    insert(
        "auth.sessions",
        row("id", session, "user_id", customer.id(), "last_seen_at", now.atOffset(UTC)));
    String selfReviewer =
        issuer.issue(
            customer.id(), Role.COMPLIANCE_OFFICER, session, now, now.plus(Duration.ofMinutes(15)));

    for (MockHttpServletResponse response :
        List.of(approve(application, selfReviewer), reject(application, "Mine", selfReviewer))) {
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
      assertThat(body(response).propertyNames())
          .containsExactlyInAnyOrder(
              "success", "errorCode", "message", "correlationId", "timestamp");
    }

    assertThat(application(application))
        .containsEntry("status", "PENDING_REVIEW")
        .containsEntry("reviewer_id", null);
    assertThat(walletsOf(customer.id())).isEmpty();
    assertThat(profile(customer.id())).containsEntry("first_name", null);
    assertThat(auditRows(application.toString()))
        .noneMatch(
            r ->
                "KYC_APPLICATION_APPROVE".equals(r.get("action"))
                    || "KYC_APPLICATION_REJECT".equals(r.get("action")));
    // Another officer can still decide it.
    assertThat(approve(application, officer().token()).getStatus()).isEqualTo(200);
  }

  @Test
  void aRejectedCustomerMayApplyAgainAndBeApproved() throws Exception {
    Account customer = customer();
    String token = officer().token();
    UUID first = pending(customer);
    assertThat(reject(first, "Unreadable", token).getStatus()).isEqualTo(200);

    UUID second = draft(customer, "FULL");
    assertThat(postAs(APPLICATIONS + "/" + second + "/submit", null, customer.token()).getStatus())
        .isEqualTo(200);
    MockHttpServletResponse approved = approve(second, token);

    assertThat(approved.getStatus()).isEqualTo(200);
    assertThat(walletsOf(customer.id()))
        .singleElement()
        .satisfies(w -> assertThat(w.get("tier")).isEqualTo("VERIFIED"));
    assertThat(application(first)).containsEntry("status", "REJECTED");
  }

  // List ---------------------------------------------------------------------------------------

  @Test
  void theListIsPagedOldestFirstWithMaskedNamesAndReferences() throws Exception {
    String token = officer().token();
    UUID oldest = pending(customer());
    UUID middle = pending(customer());
    UUID newest = pending(customer());
    UUID draft = draft(customer(), "BASIC");

    MockHttpServletResponse first = getAs(REVIEW + "?status=PENDING_REVIEW&page=0&size=2", token);
    List<JsonNode> all = allListed("status=PENDING_REVIEW", token);
    List<String> ids = all.stream().map(item -> item.get("id").asString()).toList();

    // P2: the queue is worked oldest first; the three are the newest, so they come last, in order.
    assertThat(ids.subList(ids.size() - 3, ids.size()))
        .containsExactly(oldest.toString(), middle.toString(), newest.toString());
    assertThat(ids).doesNotContain(draft.toString());
    assertThat(first.getStatus()).isEqualTo(200);
    JsonNode body = body(first);
    assertThat(body.get("items")).hasSize(2);
    JsonNode page = body.get("page");
    assertThat(page.get("page").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(2);
    long total = page.get("totalElements").asLong();
    assertThat(total).isEqualTo(ids.size());
    assertThat(page.get("totalPages").asLong()).isEqualTo((total + 1) / 2);
    JsonNode item = all.getLast();
    assertThat(item.get("maskedName").asString()).isEqualTo("Somchai J.");
    assertThat(item.get("documentReference").asString()).isEqualTo("***4567");
    assertThat(item.get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertThat(item.toString()).doesNotContain(REFERENCE, LAST_NAME);
  }

  @Test
  void theListFiltersByStatusAndDefaultsToThePendingQueue() throws Exception {
    String token = officer().token();
    UUID approved = pending(customer());
    assertThat(approve(approved, token).getStatus()).isEqualTo(200);
    UUID waiting = pending(customer());

    List<JsonNode> approvedList = allListed("status=APPROVED", token);
    List<String> defaultIds = allListedIds("", token);

    assertThat(approvedList)
        .extracting(item -> item.get("id").asString())
        .contains(approved.toString())
        .doesNotContain(waiting.toString());
    for (JsonNode item : approvedList) {
      assertThat(item.get("status").asString()).isEqualTo("APPROVED");
    }
    assertThat(defaultIds).contains(waiting.toString()).doesNotContain(approved.toString());
  }

  @ParameterizedTest
  @CsvSource({
    "status=DRAFT, status",
    "status=pending_review, status",
    "size=0, size",
    "size=101, size",
    "page=-1, page",
    "page=x, page"
  })
  void badListParametersAreValidationErrorsNamingTheParameter(String query, String name)
      throws Exception {
    MockHttpServletResponse response = getAs(REVIEW + "?" + query, officer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(response).get("message").asString()).startsWith(name + ": ");
  }

  @Test
  void theLargestPageSizeIsAccepted() throws Exception {
    assertThat(getAs(REVIEW + "?size=100", officer().token()).getStatus()).isEqualTo(200);
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    for (JsonNode item : page.get("items")) {
      ids.add(item.get("id").asString());
    }
    return ids;
  }
}
