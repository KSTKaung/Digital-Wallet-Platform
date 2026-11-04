package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.securityfixture.UrlRuleProbeController;
import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.web.ApiErrorController;
import com.portfolio.wallet.support.RejectingJwtDecoder;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Security baseline with the real filter chain, no database (web slice). {@link
 * UrlRuleProbeController} supplies endpoints without method security in each role group.
 */
@WebMvcTest(controllers = ApiErrorController.class)
@ExtendWith(OutputCaptureExtension.class)
@Import({
  SecurityConfig.class,
  JsonSecurityErrorHandler.class,
  RejectingJwtDecoder.class,
  UrlRuleProbeController.class
})
class SecurityConfigWebTest {

  @Autowired MockMvc mvc;

  @Test
  void apiIsDeniedByDefaultWithDocumentedBody() throws Exception {
    mvc.perform(get("/api/v1/wallet"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.correlationId").value(notNullValue()))
        .andExpect(header().exists(CorrelationId.HEADER));
  }

  /** Unauthenticated callers learn nothing about which resources exist: same 401 for any path. */
  @Test
  void unauthenticatedResponseDoesNotDependOnResourceExistence() throws Exception {
    for (String path :
        new String[] {"/api/v1/wallet", "/api/v1/transfers/123", "/api/v1/no-such-thing"}) {
      mvc.perform(get(path))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
          .andExpect(jsonPath("$.message").value("Authentication required"));
    }
  }

  // /api/v1/wallets/** is a CUSTOMER path in the role table, so the caller is a customer.
  @Test
  @WithMockUser(roles = "CUSTOMER")
  void authenticatedUnknownResourceIs404NotFoundWithGenericMessage() throws Exception {
    mvc.perform(get("/api/v1/wallets/00000000-0000-0000-0000-000000000001"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Resource not found"));
  }

  /** Outside every role group, an unknown path stays the generic 404 for any signed-in role. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void unknownPathOutsideTheRoleGroupsIsStill404() throws Exception {
    mvc.perform(get("/api/v1/no-such-thing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
  }

  /**
   * A staff path with an endpoint but no rule of its own is refused for every role; one without an
   * endpoint is the generic 404 for every role (J3; MVP-ADR-19), never a 403 that would reveal it.
   */
  @Test
  @WithMockUser(roles = {"ADMIN", "COMPLIANCE_OFFICER", "CUSTOMER"})
  void anUnruledStaffEndpointIsDeniedAndAnUnknownStaffPathIs404ForEveryRole() throws Exception {
    mvc.perform(get(UrlRuleProbeController.ADMIN_OTHER))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    mvc.perform(get("/api/v1/admin/no-such-thing")).andExpect(genericNotFound());
  }

  // J3: role rules apply to endpoints only; /api/v1 without a rule needs CUSTOMER -----------------

  private static ResultMatcher genericNotFound() {
    List<ResultMatcher> matchers =
        List.of(
            status().isNotFound(),
            jsonPath("$.success").value(false),
            jsonPath("$.errorCode").value("NOT_FOUND"),
            jsonPath("$.message").value("Resource not found"));
    return result -> {
      for (ResultMatcher matcher : matchers) {
        matcher.match(result);
      }
    };
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffOnUnknownPathsGetTheGeneric404(String role) throws Exception {
    for (String path :
        new String[] {
          "/api/v1/wallet/xyz", "/api/v1/admin/xyz", "/api/v1/xyz", "/api/v1/kyc/xyz/abc"
        }) {
      mvc.perform(get(path).with(user("staff").roles(role))).andExpect(genericNotFound());
      mvc.perform(post(path).with(user("staff").roles(role))).andExpect(genericNotFound());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffOnAKnownCustomerEndpointAre403(String role) throws Exception {
    for (String path :
        new String[] {
          UrlRuleProbeController.CUSTOMER_WALLET,
          UrlRuleProbeController.CUSTOMER_KYC,
          UrlRuleProbeController.OTHER_API
        }) {
      mvc.perform(get(path).with(user("staff").roles(role)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }
  }

  @Test
  void aCustomerOnAKnownStaffEndpointIs403AndOnAnUnknownPath404() throws Exception {
    for (String path :
        new String[] {
          UrlRuleProbeController.ADMIN_KYC,
          UrlRuleProbeController.ADMIN_CUSTOMERS,
          UrlRuleProbeController.ADMIN_OTHER
        }) {
      mvc.perform(get(path).with(user("customer").roles("CUSTOMER")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }
    for (String path : new String[] {"/api/v1/admin/kyc/xyz", "/api/v1/xyz", "/api/v1/wallet/x"}) {
      mvc.perform(get(path).with(user("customer").roles("CUSTOMER"))).andExpect(genericNotFound());
    }
  }

  @Test
  void theRightRoleReachesEachEndpoint() throws Exception {
    mvc.perform(get(UrlRuleProbeController.OTHER_API).with(user("c").roles("CUSTOMER")))
        .andExpect(status().isOk());
    mvc.perform(get(UrlRuleProbeController.ADMIN_KYC).with(user("o").roles("COMPLIANCE_OFFICER")))
        .andExpect(status().isOk());
    mvc.perform(get(UrlRuleProbeController.ADMIN_CUSTOMERS).with(user("a").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  /** A wrong method on a known path is still an endpoint: the wrong role gets 403, not 405. */
  @Test
  void aWrongMethodOnAKnownPathIs403ForTheWrongRoleAnd405ForTheRightOne() throws Exception {
    mvc.perform(delete(UrlRuleProbeController.CUSTOMER_WALLET).with(user("a").roles("ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    mvc.perform(post(UrlRuleProbeController.ADMIN_KYC).with(user("c").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(post(UrlRuleProbeController.ADMIN_OTHER).with(user("a").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(delete(UrlRuleProbeController.CUSTOMER_WALLET).with(user("c").roles("CUSTOMER")))
        .andExpect(status().isMethodNotAllowed());
  }

  /** Without a token, known and unknown paths get the identical 401. */
  @Test
  void anonymousCallersGetTheIdentical401OnKnownAndUnknownPaths() throws Exception {
    String reference = null;
    for (String path :
        new String[] {
          UrlRuleProbeController.CUSTOMER_WALLET,
          UrlRuleProbeController.ADMIN_KYC,
          UrlRuleProbeController.ADMIN_OTHER,
          UrlRuleProbeController.OTHER_API,
          "/api/v1/wallet/xyz",
          "/api/v1/admin/xyz",
          "/api/v1/xyz"
        }) {
      for (MockHttpServletRequestBuilder request : List.of(get(path), delete(path))) {
        MockHttpServletResponse response =
            mvc.perform(request).andExpect(status().isUnauthorized()).andReturn().getResponse();
        String body = stable(response);
        if (reference == null) {
          reference = body;
        }
        assertThat(body).as("%s %s", request, path).isEqualTo(reference);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
      }
    }
  }

  private static String stable(MockHttpServletResponse response) throws Exception {
    return response
        .getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "")
        .replaceAll("\"correlationId\":\"[^\"]*\"", "");
  }

  /** Paths outside /api/v1 keep the plain "signed in" rule. */
  @Test
  void outsideApiV1AnUnknownPathIsStill404ForAnyRole() throws Exception {
    mvc.perform(get("/no-such-thing").with(user("a").roles("ADMIN"))).andExpect(genericNotFound());
  }

  @Test
  void internalPathsDoNotExist() throws Exception {
    mvc.perform(get("/internal/v1/wallet/movements"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    mvc.perform(get("/internal")).andExpect(status().isNotFound());
  }

  /** Encoded or non-canonical spellings of /internal are blocked too, not passed to MVC. */
  @Test
  void encodedInternalPathsDoNotExist() throws Exception {
    for (String path :
        new String[] {"/%69nternal/v1/x", "/%69nternal", "//internal/v1/x", "/internal;a=b/v1/x"}) {
      // Absolute URI: the raw path is sent as written (a bare "//internal" would parse as a host).
      mvc.perform(get(URI.create("http://localhost" + path)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }
  }

  @Test
  void corsPreflightFromSpaOriginIsAllowed() throws Exception {
    mvc.perform(
            options("/api/v1/transfers")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Idempotency-Key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
  }

  @Test
  void corsPreflightFromForeignOriginIsRejected() throws Exception {
    mvc.perform(
            options("/api/v1/transfers")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
  }

  /** The documented body, not Spring's plain-text "Invalid CORS request". */
  @Test
  void aForeignOriginGetsTheDocumentedBodyOnLoginAndOtherPaths() throws Exception {
    mvc.perform(post("/api/v1/auth/login").header("Origin", "https://evil.example"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("Access denied"))
        .andExpect(jsonPath("$.correlationId").value(notNullValue()))
        .andExpect(jsonPath("$.timestamp").value(notNullValue()))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    mvc.perform(get("/api/v1/wallet").header("Origin", "https://evil.example"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
  }

  @Test
  void aForeignOriginIsLoggedOnceWithoutItsValue(CapturedOutput output) throws Exception {
    mvc.perform(get("/api/v1/wallet").header("Origin", "https://logged-marker.example"))
        .andExpect(status().isForbidden());

    assertThat(output.getAll())
        .containsOnlyOnce("Refused a cross-origin request")
        .doesNotContain("logged-marker");
  }

  @Test
  void anAllowedOriginPassesAndCorsStaysNonCredentialed() throws Exception {
    mvc.perform(get("/api/v1/wallet").header("Origin", "http://localhost:5173"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
  }

  /** Regression: the web slice once created Boot's default user and logged its password. */
  @Test
  void noGeneratedDefaultPasswordIsCreated(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContainIgnoringCase("generated security password");
  }

  @Test
  void securityHeadersArePresent() throws Exception {
    mvc.perform(get("/api/v1/wallet"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"));
  }
}
