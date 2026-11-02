package com.portfolio.wallet.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.wallet.common.security.JsonSecurityErrorHandler;
import com.portfolio.wallet.common.security.SecurityConfig;
import com.portfolio.wallet.support.RejectingJwtDecoder;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** {@code /error} with the real filter chain (documentations/06 MVP-ADR-19). */
@WebMvcTest(controllers = ApiErrorController.class)
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class, RejectingJwtDecoder.class})
class ApiErrorControllerWebTest {

  @Autowired MockMvc mvc;

  @ParameterizedTest(name = "{0} is {1}")
  @CsvSource({
    "400, VALIDATION_ERROR",
    "401, UNAUTHORIZED",
    "403, FORBIDDEN",
    "404, NOT_FOUND",
    "405, METHOD_NOT_ALLOWED",
    "406, VALIDATION_ERROR",
    "413, VALIDATION_ERROR",
    "415, UNSUPPORTED_MEDIA_TYPE",
    "429, RATE_LIMITED",
    "500, SYSTEM_ERROR",
    "503, SYSTEM_ERROR"
  })
  void containerStatusBecomesTheDocumentedBody(int status, String errorCode) throws Exception {
    mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status))
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value(errorCode))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.correlationId").isNotEmpty())
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
  }

  @Test
  void bodyHasNoPathExceptionOrDetail() throws Exception {
    String body =
        mvc.perform(
                get("/error")
                    .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                    .requestAttr(RequestDispatcher.ERROR_MESSAGE, "secret-message")
                    .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/secret-path")
                    .requestAttr(
                        RequestDispatcher.ERROR_EXCEPTION,
                        new IllegalStateException("secret-exception")))
            .andExpect(jsonPath("$.path").doesNotExist())
            .andExpect(jsonPath("$.exception").doesNotExist())
            .andExpect(jsonPath("$.trace").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("secret", "IllegalStateException");
  }

  /** {@code /error} is public, so a direct request must reveal nothing and look like a 404. */
  @Test
  void directRequestWithoutAnErrorIsAnOrdinaryNotFound() throws Exception {
    mvc.perform(get("/error"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Resource not found"));
  }

  @Test
  void aNonErrorStatusAttributeIsAlsoANotFound() throws Exception {
    mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 200))
        .andExpect(status().isNotFound());
  }
}
