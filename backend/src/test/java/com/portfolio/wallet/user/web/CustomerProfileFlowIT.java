package com.portfolio.wallet.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.portfolio.wallet.auth.domain.Role;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET} and {@code PUT /api/v1/customers/me} (documentations/02 §2.2; doc 08 §4B.2 Q15;
 * owner, 2026-11-03: the profile locks on approval).
 */
class CustomerProfileFlowIT extends UserFlowSupport {

  @Test
  void theFirstReadCreatesTheProfileAndMasksContactDetails() throws Exception {
    Account account = customer();

    MockHttpServletResponse response = getAs(PROFILE, account.token());

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = body(response);
    assertThat(body.get("email").asString()).isEqualTo("u***@example.test");
    assertThat(body.get("phoneNumber").asString())
        .isEqualTo("***" + account.phone().substring(account.phone().length() - 4));
    assertThat(body.get("firstName").isNull()).isTrue();
    assertThat(body.get("dateOfBirth").isNull()).isTrue();
    assertThat(response.getContentAsString())
        .doesNotContain(account.email(), account.phone(), account.id().toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM usr.customers WHERE user_id = ?",
                Integer.class,
                account.id()))
        .isEqualTo(1);
  }

  @Test
  void repeatedReadsKeepOneProfileRow() throws Exception {
    Account account = customer();

    getAs(PROFILE, account.token());
    getAs(PROFILE, account.token());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM usr.customers WHERE user_id = ?",
                Integer.class,
                account.id()))
        .isEqualTo(1);
  }

  @Test
  void anUpdateIsStoredReturnedAndAuditedWithoutValues() throws Exception {
    Account account = customer();
    String correlationId = UUID.randomUUID().toString();

    MockHttpServletResponse response =
        send(json(put(PROFILE), profileBody("1990-05-17", "TH")), account.token(), correlationId);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("dateOfBirth").asString()).isEqualTo("1990-05-17");
    assertThat(body(response).get("countryCode").asString()).isEqualTo("TH");
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT action, resource, resource_id, actor_id, actor_type, result,"
                + " old_value::text AS old_value, new_value::text AS new_value"
                + " FROM audit.audit_logs WHERE correlation_id = ?",
            correlationId);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("action", "CUSTOMER_PROFILE_UPDATE")
        .containsEntry("resource", "CUSTOMER")
        .containsEntry("resource_id", account.id().toString())
        .containsEntry("actor_id", account.id())
        .containsEntry("actor_type", "CUSTOMER")
        .containsEntry("result", "SUCCESS")
        .containsEntry("old_value", null)
        .containsEntry("new_value", null);
  }

  @Test
  void todayIsAValidDateOfBirthButTomorrowIsNot() throws Exception {
    Account account = customer();
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Bangkok"));

    assertThat(putAs(PROFILE, profileBody(today.toString(), "TH"), account.token()).getStatus())
        .isEqualTo(200);
    MockHttpServletResponse future =
        putAs(PROFILE, profileBody(today.plusDays(1).toString(), "TH"), account.token());
    assertThat(future.getStatus()).isEqualTo(400);
    assertThat(errorCode(future)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(future).get("message").asString())
        .isEqualTo("dateOfBirth: must not be in the future");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"th", "XX", "THA", ""})
  void anInvalidCountryIsAValidationErrorNamingTheField(String code) throws Exception {
    MockHttpServletResponse response =
        putAs(PROFILE, profileBody("1990-05-17", code), customer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(response).get("message").asString()).startsWith("countryCode: ");
    if (!code.isEmpty()) {
      assertThat(body(response).get("message").asString()).doesNotContain("\"" + code + "\"");
    }
  }

  @Test
  void aMissingDateOfBirthIsAValidationError() throws Exception {
    MockHttpServletResponse response = putAs(PROFILE, profileBody(null, "TH"), customer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(body(response).get("message").asString()).startsWith("dateOfBirth: ");
  }

  @Test
  void anApprovedApplicationLocksTheProfile() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID application = draft(account);
    assertThat(submit(application, account.token()).getStatus()).isEqualTo(200);
    // The decision written directly, as the approval writes it (with the legal name, N3).
    approveWithLegalName(application);

    MockHttpServletResponse response =
        putAs(PROFILE, profileBody("1991-01-01", "GB"), account.token());

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(errorCode(response)).isEqualTo("INVALID_KYC_STATE");
    JsonNode profile = body(getAs(PROFILE, account.token()));
    assertThat(profile.get("dateOfBirth").asString()).isEqualTo("1990-05-17");
    assertThat(profile.get("countryCode").asString()).isEqualTo("TH");
  }

  // Owner follow-up #2 (2026-11-03): locked from submit onward, unlocked by a rejection ---------

  @Test
  void aDraftLeavesTheProfileEditable() throws Exception {
    Account account = customer();
    completeProfile(account);
    draft(account);

    assertThat(putAs(PROFILE, profileBody("1991-01-01", "GB"), account.token()).getStatus())
        .isEqualTo(200);
  }

  @Test
  void aSubmittedApplicationLocksTheProfile() throws Exception {
    Account account = customer();
    completeProfile(account);
    assertThat(submit(draft(account), account.token()).getStatus()).isEqualTo(200);

    MockHttpServletResponse response =
        putAs(PROFILE, profileBody("1991-01-01", "GB"), account.token());

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(errorCode(response)).isEqualTo("INVALID_KYC_STATE");
    assertThat(body(response).get("message").asString())
        .isEqualTo("The profile cannot change while a KYC application is under review or approved")
        .doesNotContain("1991", "GB");
    JsonNode profile = body(getAs(PROFILE, account.token()));
    assertThat(profile.get("dateOfBirth").asString()).isEqualTo("1990-05-17");
    assertThat(profile.get("countryCode").asString()).isEqualTo("TH");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'CUSTOMER_PROFILE_UPDATE'"
                    + " AND actor_id = ?",
                Integer.class,
                account.id()))
        .as("only the first, successful update is audited")
        .isEqualTo(1);
  }

  @Test
  void aRejectionUnlocksTheProfileAndTheNextSubmitLocksItAgain() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID first = draft(account);
    submit(first, account.token());
    jdbc.update(
        "UPDATE usr.kyc_applications SET status = 'REJECTED', reviewer_id = ?,"
            + " rejection_reason = 'The date of birth does not match' WHERE id = ?",
        UUID.randomUUID(),
        first);

    assertThat(putAs(PROFILE, profileBody("1991-01-01", "TH"), account.token()).getStatus())
        .as("corrected after the rejection")
        .isEqualTo(200);
    UUID second = draft(account);
    assertThat(submit(second, account.token()).getStatus()).isEqualTo(200);

    MockHttpServletResponse locked =
        putAs(PROFILE, profileBody("1992-01-01", "TH"), account.token());
    assertThat(locked.getStatus()).isEqualTo(409);
    assertThat(errorCode(locked)).isEqualTo("INVALID_KYC_STATE");
    assertThat(body(getAs(PROFILE, account.token())).get("dateOfBirth").asString())
        .isEqualTo("1991-01-01");
  }

  @Test
  void aCopiedLegalNameLocksTheProfileAndIsShownToItsOwner() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID application = draft(account);
    assertThat(submit(application, account.token()).getStatus()).isEqualTo(200);
    // The approval's copy, as KycReviewService does it: V8 accepts the name only from the
    // application approved in the same transaction (J4).
    inTransaction(
        c -> {
          update(
              c,
              "UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = ? WHERE id = ?",
              UUID.randomUUID(),
              application);
          update(
              c,
              "UPDATE usr.customers SET first_name = ?, last_name = ? WHERE user_id = ?",
              FIRST_NAME,
              LAST_NAME,
              account.id());
        });

    assertThat(body(getAs(PROFILE, account.token())).get("firstName").asString())
        .isEqualTo(FIRST_NAME);
    MockHttpServletResponse response =
        putAs(PROFILE, profileBody("1990-05-17", "GB"), account.token());
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(errorCode(response)).isEqualTo("INVALID_KYC_STATE");
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffGetForbidden(Role role) throws Exception {
    String token = account(role).token();

    assertThat(getAs(PROFILE, token).getStatus()).isEqualTo(403);
    MockHttpServletResponse put = putAs(PROFILE, profileBody("1990-05-17", "TH"), token);
    assertThat(put.getStatus()).isEqualTo(403);
    assertThat(errorCode(put)).isEqualTo("FORBIDDEN");
  }

  @Test
  void withoutATokenItIs401() throws Exception {
    assertThat(getAs(PROFILE, null).getStatus()).isEqualTo(401);
    assertThat(putAs(PROFILE, profileBody("1990-05-17", "TH"), null).getStatus()).isEqualTo(401);
  }
}
