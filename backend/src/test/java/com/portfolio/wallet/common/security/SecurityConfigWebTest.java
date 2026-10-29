package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.wallet.common.web.CorrelationId;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** Security baseline with the real filter chain, no database (web slice). */
@WebMvcTest
@ExtendWith(OutputCaptureExtension.class)
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class})
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

  @Test
  @WithMockUser
  void authenticatedUnknownResourceIs404NotFoundWithGenericMessage() throws Exception {
    mvc.perform(get("/api/v1/wallets/00000000-0000-0000-0000-000000000001"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Resource not found"));
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
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
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
