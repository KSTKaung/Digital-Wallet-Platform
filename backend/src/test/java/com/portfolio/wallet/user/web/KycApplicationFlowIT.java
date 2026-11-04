package com.portfolio.wallet.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.auth.domain.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * The customer's KYC endpoints (documentations/02 §2.2; doc 06 MVP-ADR-10 amendment, -19; doc 08
 * §4B.2 Q6–Q8; owner, 2026-11-03: submit needs a complete profile). Includes the ownership test
 * owed since PR 3: another customer's application gives the identical 404 on GET, PUT and submit.
 */
class KycApplicationFlowIT extends UserFlowSupport {

  private String applicationPath(UUID id) {
    return APPLICATIONS + "/" + id;
  }

  // Create -------------------------------------------------------------------------------------

  @Test
  void createReturns201WithADraftTheMaskedReferenceAndALocation() throws Exception {
    Account account = customer();

    MockHttpServletResponse response = createApplication(account.token());

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = body(response);
    UUID id = UUID.fromString(body.get("id").asString());
    assertThat(response.getHeader("Location")).isEqualTo(applicationPath(id));
    assertThat(body.get("status").asString()).isEqualTo("DRAFT");
    assertThat(body.get("verificationLevel").asString()).isEqualTo("BASIC");
    assertThat(body.get("documentType").asString()).isEqualTo("ID_CARD");
    assertThat(body.get("documentReference").asString()).isEqualTo("***4567");
    assertThat(body.get("firstName").asString()).isEqualTo(FIRST_NAME);
    assertThat(body.get("submittedAt").isNull()).isTrue();
    assertThat(response.getContentAsString()).doesNotContain(REFERENCE);
    assertThat(
            jdbc.queryForObject(
                "SELECT customer_id FROM usr.kyc_applications WHERE id = ?", UUID.class, id))
        .isEqualTo(account.id());
  }

  @Test
  void aSecondActiveApplicationIsKycAlreadyActive() throws Exception {
    Account account = customer();
    draft(account);

    MockHttpServletResponse second = createApplication(account.token());

    assertThat(second.getStatus()).isEqualTo(409);
    assertThat(errorCode(second)).isEqualTo("KYC_ALREADY_ACTIVE");
  }

  @Test
  void aRejectedCustomerMayApplyAgain() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID first = draft(account);
    submit(first, account.token());
    // Staff decisions arrive in PR 4a step 3; until then the rejection is written directly.
    jdbc.update(
        "UPDATE usr.kyc_applications SET status = 'REJECTED', reviewer_id = ?,"
            + " rejection_reason = 'The document photo is unreadable' WHERE id = ?",
        UUID.randomUUID(),
        first);

    JsonNode rejected = body(getAs(applicationPath(first), account.token()));
    assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
    assertThat(rejected.get("rejectionReason").asString())
        .isEqualTo("The document photo is unreadable");
    assertThat(rejected.get("decidedAt").isNull()).isFalse();
    assertThat(createApplication(account.token()).getStatus()).isEqualTo(201);
  }

  /** The partial unique index decides: exactly one of N concurrent creates succeeds. */
  @Test
  void concurrentCreatesMakeExactlyOneApplication() throws Exception {
    Account account = customer();
    int attempts = 6;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < attempts; i++) {
        Callable<Integer> create =
            () -> {
              start.await();
              return createApplication(account.token()).getStatus();
            };
        results.add(pool.submit(create));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }

      assertThat(statuses).containsOnly(201, 409);
      assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM usr.kyc_applications WHERE customer_id = ?",
                Integer.class,
                account.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM usr.customers WHERE user_id = ?",
                Integer.class,
                account.id()))
        .isEqualTo(1);
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource(
      nullValues = "NULL",
      value = {
        "verificationLevel, PREMIUM",
        "verificationLevel, NULL",
        "documentType, DRIVING_LICENCE",
        "documentReference, ' '",
        "documentReference, NULL",
        "firstName, ''",
        "lastName, NULL"
      })
  void invalidFieldsAreValidationErrorsNamingTheFieldOnly(String field, String value)
      throws Exception {
    MockHttpServletResponse response =
        postAs(APPLICATIONS, kycBodyWith(field, value), customer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    String message = body(response).get("message").asString();
    assertThat(message).startsWith(field + ": ");
    if (value != null && !value.isBlank()) {
      assertThat(message).doesNotContain(value);
    }
  }

  @Test
  void lengthsAndControlCharactersAreRefused() throws Exception {
    String token = customer().token();

    for (Map<String, Object> body :
        List.of(
            kycBodyWith("documentReference", "x".repeat(51)),
            kycBodyWith("firstName", "x".repeat(101)),
            kycBodyWith("lastName", "Jai\u0000dee"),
            kycBodyWith("documentReference", "AB\n123"))) {
      MockHttpServletResponse response = postAs(APPLICATIONS, body, token);
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    }
    assertThat(
            postAs(APPLICATIONS, kycBodyWith("documentReference", "x".repeat(50)), token)
                .getStatus())
        .isEqualTo(201);
  }

  @Test
  void aShortReferenceIsFullyMasked() throws Exception {
    MockHttpServletResponse response =
        postAs(APPLICATIONS, kycBodyWith("documentReference", "A12"), customer().token());

    assertThat(body(response).get("documentReference").asString()).isEqualTo("***");
  }

  // Edit ---------------------------------------------------------------------------------------

  @Test
  void aDraftCanBeEdited() throws Exception {
    Account account = customer();
    UUID id = draft(account);
    Map<String, Object> changed = kycBodyWith("documentReference", "PP7654321");
    changed.put("documentType", "PASSPORT");
    changed.put("verificationLevel", "FULL");

    MockHttpServletResponse response = putAs(applicationPath(id), changed, account.token());

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = body(response);
    assertThat(body.get("documentReference").asString()).isEqualTo("***4321");
    assertThat(body.get("documentType").asString()).isEqualTo("PASSPORT");
    assertThat(body.get("verificationLevel").asString()).isEqualTo("FULL");
    assertThat(body.get("status").asString()).isEqualTo("DRAFT");
  }

  @Test
  void editAfterSubmitIsInvalidKycStateAndChangesNothing() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID id = draft(account);
    assertThat(submit(id, account.token()).getStatus()).isEqualTo(200);

    MockHttpServletResponse response =
        putAs(applicationPath(id), kycBodyWith("firstName", "Changed"), account.token());

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(errorCode(response)).isEqualTo("INVALID_KYC_STATE");
    assertThat(
            jdbc.queryForObject(
                "SELECT first_name FROM usr.kyc_applications WHERE id = ?", String.class, id))
        .isEqualTo(FIRST_NAME);
  }

  // Submit -------------------------------------------------------------------------------------

  @Test
  void submitMovesToPendingReviewWithAServerTime() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID id = draft(account);

    MockHttpServletResponse response = submit(id, account.token());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertThat(body(response).get("submittedAt").isNull()).isFalse();
    MockHttpServletResponse again = submit(id, account.token());
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(errorCode(again)).isEqualTo("INVALID_KYC_STATE");
  }

  /** Owner, 2026-11-03: creating and editing stay possible without the profile; submit is not. */
  @Test
  void submitNeedsTheDateOfBirthThenTheCountry() throws Exception {
    Account account = customer();
    UUID id = draft(account);
    assertThat(putAs(applicationPath(id), kycBody(), account.token()).getStatus()).isEqualTo(200);

    MockHttpServletResponse noProfile = submit(id, account.token());
    assertThat(noProfile.getStatus()).isEqualTo(400);
    assertThat(errorCode(noProfile)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(noProfile).get("message").asString()).startsWith("dateOfBirth: ");

    // Only the date of birth: the profile endpoint sets both, so the gap is made directly.
    jdbc.update(
        "UPDATE usr.customers SET date_of_birth = '1990-05-17' WHERE user_id = ?", account.id());
    MockHttpServletResponse noCountry = submit(id, account.token());
    assertThat(noCountry.getStatus()).isEqualTo(400);
    assertThat(body(noCountry).get("message").asString()).startsWith("countryCode: ");

    completeProfile(account);
    assertThat(submit(id, account.token()).getStatus()).isEqualTo(200);
  }

  // Reads --------------------------------------------------------------------------------------

  @Test
  void statusIsNoneThenFollowsTheApplication() throws Exception {
    Account account = customer();

    JsonNode none = body(getAs(STATUS, account.token()));
    assertThat(none.get("status").asString()).isEqualTo("NONE");
    assertThat(none.get("verificationLevel").isNull()).isTrue();
    assertThat(none.get("walletCreated").asBoolean()).isFalse();

    completeProfile(account);
    UUID id = draft(account);
    assertThat(body(getAs(STATUS, account.token())).get("status").asString()).isEqualTo("DRAFT");
    submit(id, account.token());
    JsonNode pending = body(getAs(STATUS, account.token()));
    assertThat(pending.get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertThat(pending.get("verificationLevel").asString()).isEqualTo("BASIC");
    assertThat(pending.get("walletCreated").asBoolean()).isFalse();
  }

  // Ownership: identical 404 (MVP-ADR-19) -------------------------------------------------------

  @Test
  void anotherCustomersApplicationIsIndistinguishableFromAMissingOne() throws Exception {
    Account owner = customer();
    completeProfile(owner);
    UUID theirs = draft(owner);
    Account intruder = customer();
    completeProfile(intruder);
    UUID missing = UUID.randomUUID();

    List<MockHttpServletResponse> foreign =
        List.of(
            getAs(applicationPath(theirs), intruder.token()),
            putAs(applicationPath(theirs), kycBody(), intruder.token()),
            submit(theirs, intruder.token()));
    List<MockHttpServletResponse> absent =
        List.of(
            getAs(applicationPath(missing), intruder.token()),
            putAs(applicationPath(missing), kycBody(), intruder.token()),
            submit(missing, intruder.token()));

    for (int i = 0; i < foreign.size(); i++) {
      assertThat(foreign.get(i).getStatus()).isEqualTo(404);
      assertThat(stable(foreign.get(i))).isEqualTo(stable(absent.get(i)));
      assertThat(errorCode(foreign.get(i))).isEqualTo("NOT_FOUND");
    }
    // Nothing happened to the owner's application.
    JsonNode stored = body(getAs(applicationPath(theirs), owner.token()));
    assertThat(stored.get("status").asString()).isEqualTo("DRAFT");
    assertThat(stored.get("firstName").asString()).isEqualTo(FIRST_NAME);
  }

  @Test
  void aMalformedIdIsAValidationErrorNotALeak() throws Exception {
    MockHttpServletResponse response = getAs(APPLICATIONS + "/not-a-uuid", customer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(body(response).get("message").asString()).isEqualTo("id: invalid value");
  }

  // Roles --------------------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffGetForbiddenOnEveryEndpoint(Role role) throws Exception {
    Account customer = customer();
    UUID id = draft(customer);
    String staff = account(role).token();

    List<MockHttpServletResponse> responses =
        List.of(
            createApplication(staff),
            putAs(applicationPath(id), kycBody(), staff),
            getAs(applicationPath(id), staff),
            submit(id, staff),
            getAs(STATUS, staff));
    for (MockHttpServletResponse response : responses) {
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
    }
  }

  @Test
  void withoutATokenEveryEndpointIs401() throws Exception {
    UUID id = UUID.randomUUID();

    assertThat(createApplication(null).getStatus()).isEqualTo(401);
    assertThat(putAs(applicationPath(id), kycBody(), null).getStatus()).isEqualTo(401);
    assertThat(getAs(applicationPath(id), null).getStatus()).isEqualTo(401);
    assertThat(send(post(applicationPath(id) + "/submit"), null).getStatus()).isEqualTo(401);
    assertThat(getAs(STATUS, null).getStatus()).isEqualTo(401);
  }

  // Audit --------------------------------------------------------------------------------------

  @Test
  void createEditAndSubmitAreAuditedWithStatesOnly() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID id = draft(account);
    putAs(applicationPath(id), kycBody(), account.token());
    submit(id, account.token());

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT action, actor_type, result, old_value ->> 'status' AS old_status,"
                + " new_value ->> 'status' AS new_status FROM audit.audit_logs"
                + " WHERE resource = 'KYC_APPLICATION' AND resource_id = ?"
                + " ORDER BY occurred_at, id",
            id.toString());

    assertThat(rows)
        .extracting(r -> r.get("action"))
        .containsExactly(
            "KYC_APPLICATION_CREATE", "KYC_APPLICATION_UPDATE", "KYC_APPLICATION_SUBMIT");
    assertThat(rows).extracting(r -> r.get("actor_type")).containsOnly("CUSTOMER");
    assertThat(rows)
        .extracting(r -> r.get("new_status"))
        .containsExactly("DRAFT", "DRAFT", "PENDING_REVIEW");
    assertThat(rows).extracting(r -> r.get("old_status")).containsExactly(null, "DRAFT", "DRAFT");
  }
}
