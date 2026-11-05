package com.portfolio.wallet.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.portfolio.securityfixture.UrlRuleProbeController;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.common.security.CorsProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The central role table in {@code SecurityConfig} over real HTTP (owner decisions A–D, PR 4a):
 * least privilege per endpoint group, refused before the body is read, with {@code @PreAuthorize}
 * as the second layer, ownership in the services, and the public paths unchanged.
 */
class AccessControlIT extends AdminFlowSupport {

  private static final String MALFORMED = "{\"reason\": ";

  @Autowired CorsProperties cors;

  private MockHttpServletResponse malformedReject(String token) throws Exception {
    return send(
        post(REVIEW + "/" + UUID.randomUUID() + "/reject")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED),
        token);
  }

  private void assertForbidden(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
  }

  // C: the owner's cases -----------------------------------------------------------------------

  @Test
  void aCustomerWithAMalformedBodyOnAStaffEndpointIsRefusedBeforeTheBodyIsRead() throws Exception {
    assertForbidden(malformedReject(customer().token()));
  }

  @Test
  void aComplianceOfficerWithAMalformedBodyGetsTheValidationError() throws Exception {
    MockHttpServletResponse response = malformedReject(officer().token());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void aComplianceOfficerIsRefusedOnAnAdminOnlyPath() throws Exception {
    assertForbidden(getAs(UrlRuleProbeController.ADMIN_CUSTOMERS, officer().token()));
    assertThat(
            getAs(UrlRuleProbeController.ADMIN_CUSTOMERS, account(Role.ADMIN).token()).getStatus())
        .isEqualTo(200);
  }

  @Test
  void anAdminIsRefusedOnEveryKycDecisionEndpoint() throws Exception {
    String admin = account(Role.ADMIN).token();
    UUID application = pending(customer());

    List<MockHttpServletResponse> responses =
        List.of(
            getAs(REVIEW, admin),
            getAs(REVIEW + "/" + application, admin),
            approve(application, admin),
            reject(application, "Unreadable", admin),
            getAs(UrlRuleProbeController.ADMIN_KYC, admin));
    for (MockHttpServletResponse response : responses) {
      assertForbidden(response);
    }
    assertThat(application(application)).containsEntry("status", "PENDING_REVIEW");
    assertThat(getAs(UrlRuleProbeController.ADMIN_KYC, officer().token()).getStatus())
        .isEqualTo(200);
  }

  /** Doc 08 §4B.4 AC 4: customers get 403 on every decision endpoint, their own application too. */
  @Test
  void aCustomerIsRefusedOnEveryKycDecisionEndpointEvenForTheirOwnApplication() throws Exception {
    Account customer = customer();
    UUID own = pending(customer);
    UUID other = pending(customer());

    for (UUID application : List.of(own, other)) {
      List<MockHttpServletResponse> responses =
          List.of(
              getAs(REVIEW, customer.token()),
              getAs(REVIEW + "/" + application, customer.token()),
              approve(application, customer.token()),
              reject(application, "Unreadable", customer.token()));
      for (MockHttpServletResponse response : responses) {
        assertForbidden(response);
      }
      assertThat(application(application)).containsEntry("status", "PENDING_REVIEW");
    }
    assertThat(walletsOf(customer.id())).isEmpty();
  }

  @Test
  void withoutATokenAStaffRequestGetsTheSame401AsEveryOtherPath() throws Exception {
    MockHttpServletResponse reference = getAs("/api/v1/no-such-thing", null);
    assertThat(reference.getStatus()).isEqualTo(401);

    for (MockHttpServletResponse response :
        List.of(
            getAs(REVIEW, null),
            approve(UUID.randomUUID(), null),
            malformedReject(null),
            getAs(UrlRuleProbeController.ADMIN_CUSTOMERS, null),
            getAs(UrlRuleProbeController.ADMIN_OTHER, null),
            getAs("/api/v1/admin/no-such-thing", null),
            getAs(STATUS, null))) {
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(stable(response)).isEqualTo(stable(reference));
    }
  }

  @Test
  void aCustomerGetsTheirOwnResources() throws Exception {
    Account customer = customer();
    UUID application = draft(customer, "BASIC");

    assertThat(getAs(PROFILE, customer.token()).getStatus()).isEqualTo(200);
    assertThat(getAs(STATUS, customer.token()).getStatus()).isEqualTo(200);
    assertThat(getAs(APPLICATIONS + "/" + application, customer.token()).getStatus())
        .isEqualTo(200);
    assertThat(getAs(UrlRuleProbeController.CUSTOMER_KYC, customer.token()).getStatus())
        .isEqualTo(200);
    assertThat(getAs(UrlRuleProbeController.CUSTOMER_WALLET, customer.token()).getStatus())
        .isEqualTo(200);
  }

  /** The approved decision is the identical 404 (MVP-ADR-19), not 403. */
  @Test
  void anotherCustomersApplicationIsTheIdentical404() throws Exception {
    UUID foreign = draft(customer(), "BASIC");
    String intruder = customer().token();

    MockHttpServletResponse theirs = getAs(APPLICATIONS + "/" + foreign, intruder);
    MockHttpServletResponse missing = getAs(APPLICATIONS + "/" + UUID.randomUUID(), intruder);
    MockHttpServletResponse submitTheirs =
        postAs(APPLICATIONS + "/" + foreign + "/submit", null, intruder);

    assertThat(theirs.getStatus()).isEqualTo(404);
    assertThat(stable(theirs)).isEqualTo(stable(missing));
    assertThat(stable(submitTheirs)).isEqualTo(stable(missing));
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffAreRefusedOnCustomerPaths(Role role) throws Exception {
    String staff = account(role).token();

    for (MockHttpServletResponse response :
        List.of(
            getAs(PROFILE, staff),
            getAs(STATUS, staff),
            postAs(APPLICATIONS, kycBody("BASIC"), staff),
            // A malformed body is refused before it is read, as on staff paths.
            send(post(APPLICATIONS).contentType(MediaType.APPLICATION_JSON).content("{"), staff),
            getAs(UrlRuleProbeController.CUSTOMER_KYC, staff),
            getAs(UrlRuleProbeController.CUSTOMER_WALLET, staff),
            getAs("/api/v1/wallet", staff),
            getAs("/api/v1/wallet/balance", staff))) {
      assertForbidden(response);
    }
  }

  @ParameterizedTest
  @EnumSource(Role.class)
  void anUnruledStaffEndpointIsDeniedAndAnUnknownStaffPathIsTheGeneric404ForEveryRole(Role role)
      throws Exception {
    String token = account(role).token();

    // A handler exists here, without @PreAuthorize: only the URL rule refuses it.
    assertForbidden(getAs(UrlRuleProbeController.ADMIN_OTHER, token));
    // No handler: the generic 404, never a 403 that would reveal the path (J3; MVP-ADR-19).
    MockHttpServletResponse reference = getAs("/api/v1/no-such-thing", token);
    for (MockHttpServletResponse response :
        List.of(
            getAs("/api/v1/admin/no-such-thing", token),
            getAs("/api/v1/admin", token),
            send(post("/api/v1/admin/transfer-reviews/x/approve"), token))) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(stable(response)).isEqualTo(stable(reference));
    }
  }

  /** J3: unknown paths in every group are the generic 404 for staff and customers alike. */
  @ParameterizedTest
  @EnumSource(Role.class)
  void unknownPathsInEveryRoleGroupAreTheGeneric404(Role role) throws Exception {
    String token = account(role).token();
    MockHttpServletResponse reference = getAs("/api/v1/no-such-thing", token);
    assertThat(reference.getStatus()).isEqualTo(404);
    assertThat(errorCode(reference)).isEqualTo("NOT_FOUND");

    for (String path :
        List.of(
            "/api/v1/wallet/xyz",
            // The lookup itself exists since PR 4b; an unknown path in the same group.
            "/api/v1/wallets/xyz",
            "/api/v1/kyc/xyz",
            "/api/v1/customers/me/xyz",
            "/api/v1/admin/kyc/xyz/abc/def",
            "/api/v1/admin/customers/" + UUID.randomUUID() + "/suspend",
            "/api/v1/xyz")) {
      MockHttpServletResponse response = getAs(path, token);
      assertThat(response.getStatus()).as(path).isEqualTo(404);
      assertThat(stable(response)).as(path).isEqualTo(stable(reference));
    }
  }

  /** J3: a path under /api/v1 outside every group is a customer endpoint. */
  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffAreRefusedOnAnApiEndpointWithoutItsOwnRule(Role role) throws Exception {
    assertForbidden(getAs(UrlRuleProbeController.OTHER_API, account(role).token()));
    assertThat(getAs(UrlRuleProbeController.OTHER_API, customer().token()).getStatus())
        .isEqualTo(200);
  }

  /** A wrong method on a known path for the wrong role is 403, not a 405 that reveals the path. */
  @Test
  void aWrongMethodOnAKnownPathIsRefusedForTheWrongRole() throws Exception {
    String admin = account(Role.ADMIN).token();
    assertForbidden(send(put("/api/v1/wallet/freeze"), admin));
    assertForbidden(send(put(REVIEW + "/" + UUID.randomUUID() + "/approve"), customer().token()));
    assertThat(send(put("/api/v1/wallet/freeze"), customer().token()).getStatus()).isEqualTo(405);
  }

  @Test
  void anonymousCallersGetTheSame401OnKnownAndUnknownPaths() throws Exception {
    MockHttpServletResponse reference = getAs("/api/v1/no-such-thing", null);
    assertThat(reference.getStatus()).isEqualTo(401);

    for (MockHttpServletResponse response :
        List.of(
            getAs("/api/v1/wallet", null),
            getAs("/api/v1/wallet/xyz", null),
            getAs("/api/v1/admin/xyz", null),
            getAs(UrlRuleProbeController.OTHER_API, null),
            send(put("/api/v1/wallet/freeze"), null))) {
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(stable(response)).isEqualTo(stable(reference));
    }
  }

  @Test
  void aCustomerIsRefusedOnTheAdminOnlyPath() throws Exception {
    assertForbidden(getAs(UrlRuleProbeController.ADMIN_CUSTOMERS, customer().token()));
  }

  // Signed-in auth endpoints: any role ---------------------------------------------------------

  @ParameterizedTest
  @EnumSource(Role.class)
  void everyRoleMayLogOutAndReachChangePassword(Role role) throws Exception {
    Account account = account(role);

    MockHttpServletResponse changePassword =
        send(
            post("/api/v1/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
            account.token());
    MockHttpServletResponse logout =
        send(
            post("/api/v1/auth/logout")
                .header("Origin", cors.allowedOrigins().getFirst())
                .header("X-Requested-With", "XMLHttpRequest"),
            account.token());

    assertThat(changePassword.getStatus()).isEqualTo(400);
    assertThat(errorCode(changePassword)).isEqualTo("VALIDATION_ERROR");
    assertThat(logout.getStatus()).isEqualTo(204);
  }

  // D: public paths unchanged ------------------------------------------------------------------

  @Test
  void healthErrorAndPublicAuthEndpointsWorkWithoutAToken() throws Exception {
    assertThat(getAs("/actuator/health", null).getStatus()).isEqualTo(200);
    assertThat(getAs("/actuator/health/liveness", null).getStatus()).isEqualTo(200);

    MockHttpServletResponse error = getAs("/error", null);
    assertThat(error.getStatus()).isEqualTo(404);
    assertThat(errorCode(error)).isEqualTo("NOT_FOUND");

    MockHttpServletResponse login =
        postAs(
            "/api/v1/auth/login",
            Map.of("email", "nobody-" + UUID.randomUUID() + "@example.test", "password", "x"),
            null);
    assertThat(login.getStatus()).isEqualTo(401);
    assertThat(errorCode(login)).isEqualTo("UNAUTHORIZED");
    assertThat(
            postAs(
                    "/api/v1/auth/forgot-password",
                    Map.of("email", "nobody-" + UUID.randomUUID() + "@example.test"),
                    null)
                .getStatus())
        .isEqualTo(202);
    MockHttpServletResponse register =
        postAs("/api/v1/auth/register", Map.of("email", "not-an-email"), null);
    assertThat(register.getStatus()).isEqualTo(400);
  }

  /** A stale token does not fail a public endpoint, as before. */
  @Test
  void aStaffTokenOnAPublicEndpointIsIgnored() throws Exception {
    MockHttpServletResponse response =
        send(
            post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.test\"}"),
            "not-a-jwt");

    assertThat(response.getStatus()).isEqualTo(202);
  }

  @Test
  void anUnknownPathOutsideTheRoleGroupsIsStillTheGeneric404() throws Exception {
    for (Role role : Role.values()) {
      MockHttpServletResponse response = send(get("/api/v1/no-such-thing"), account(role).token());
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(errorCode(response)).isEqualTo("NOT_FOUND");
    }
  }
}
