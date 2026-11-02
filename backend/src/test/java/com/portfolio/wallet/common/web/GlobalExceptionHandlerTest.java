package com.portfolio.wallet.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.security.AccessDenialAuditor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Error-body shape and mapping (documentations/02 §1.7), without a Spring context. */
class GlobalExceptionHandlerTest {

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ProbeController())
          .setControllerAdvice(
              new GlobalExceptionHandler(
                  new StaticListableBeanFactory().getBeanProvider(AccessDenialAuditor.class)))
          .addFilters(new CorrelationIdFilter())
          .build();

  @Test
  void apiExceptionUsesItsCodeStatusAndDocumentedShape() throws Exception {
    mvc.perform(get("/probe/insufficient").header(CorrelationId.HEADER, "corr-1"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_BALANCE"))
        .andExpect(jsonPath("$.message").value("Available balance is lower than the amount"))
        .andExpect(jsonPath("$.correlationId").value("corr-1"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
  }

  @Test
  void validationErrorNamesTheFieldButNotTheRejectedValue() throws Exception {
    mvc.perform(
            post("/probe/amount")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": -99.5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("amount")))
        .andExpect(jsonPath("$.message").value(not(containsString("-99.5"))));
  }

  @Test
  void malformedBodyIsValidationError() throws Exception {
    mvc.perform(post("/probe/amount").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
  }

  @Test
  void unexpectedExceptionHidesInternals() throws Exception {
    mvc.perform(get("/probe/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errorCode").value("SYSTEM_ERROR"))
        .andExpect(jsonPath("$.message").value(not(containsString("secret-detail"))));
  }

  @Test
  void unsupportedMethodIs405MethodNotAllowed() throws Exception {
    mvc.perform(post("/probe/insufficient"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
  }

  /** A missing id and another user's id must be indistinguishable (no existence disclosure). */
  @Test
  void missingAndForeignResourcesProduceIdenticalNotFound() throws Exception {
    String missing =
        mvc.perform(get("/probe/wallets/missing").header(CorrelationId.HEADER, "same"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String foreign =
        mvc.perform(get("/probe/wallets/foreign").header(CorrelationId.HEADER, "same"))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(withoutTimestamp(foreign)).isEqualTo(withoutTimestamp(missing));
    assertThat(foreign).doesNotContain("foreign", "owner", "exists");
  }

  // Deferred gaps from the PR 1 security review (documentations/06 MVP-ADR-19) -----------------

  private static void authenticateAsUser() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "user", null, AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/probe/authorization-denied", "/probe/access-denied"})
  void accessDeniedIsForbiddenForAnAuthenticatedCaller(String path) throws Exception {
    authenticateAsUser();

    mvc.perform(get(path))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value(not(containsString("secret-detail"))));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/probe/authorization-denied", "/probe/access-denied"})
  void accessDeniedIsUnauthorizedWithNoAuthentication(String path) throws Exception {
    mvc.perform(get(path))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message").value("Authentication required"));
  }

  @Test
  void accessDeniedIsUnauthorizedForTheAnonymousToken() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    mvc.perform(get("/probe/authorization-denied"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
  }

  @Test
  void authenticationExceptionFromMethodSecurityIsUnauthorized() throws Exception {
    mvc.perform(get("/probe/no-credentials"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message").value(not(containsString("secret-detail"))));
  }

  @Test
  void missingHeaderIsValidationErrorNamingTheHeader() throws Exception {
    mvc.perform(get("/probe/header"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")));
  }

  @Test
  void headerOfTheWrongTypeNamesTheHeaderButNotTheValue() throws Exception {
    mvc.perform(get("/probe/header").header("Idempotency-Key", "secret-value"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")))
        .andExpect(jsonPath("$.message").value(not(containsString("secret-value"))));
  }

  @Test
  void pathVariableOfTheWrongTypeNamesTheFieldButNotTheValue() throws Exception {
    mvc.perform(get("/probe/typed/secret-value"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("id")))
        .andExpect(jsonPath("$.message").value(not(containsString("secret-value"))));
  }

  @Test
  void unsupportedMediaTypeIs415() throws Exception {
    mvc.perform(post("/probe/amount").contentType(MediaType.TEXT_PLAIN).content("secret-value"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
        .andExpect(jsonPath("$.message").value(not(containsString("text/plain"))));
  }

  @Test
  void missingParameterIsValidationErrorNamingTheParameter() throws Exception {
    mvc.perform(get("/probe/param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("count")));
  }

  @Test
  void missingCookieIsValidationErrorNamingTheCookie() throws Exception {
    mvc.perform(get("/probe/cookie"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("probe_cookie")));
  }

  @Test
  void methodValidationNamesTheParameterButNotTheValue() throws Exception {
    mvc.perform(get("/probe/param").param("count", "-98765"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("count")))
        .andExpect(jsonPath("$.message").value(not(containsString("98765"))));
  }

  private static String withoutTimestamp(String body) {
    return body.replaceAll("\"timestamp\":\"[^\"]*\"", "");
  }

  @RestController
  static class ProbeController {

    // Simulates a service that must not reveal whether another user's wallet exists.
    @GetMapping("/probe/wallets/{id}")
    String wallet(@PathVariable String id) {
      throw ApiException.notFound();
    }

    @GetMapping("/probe/insufficient")
    String insufficient() {
      throw new ApiException(
          ErrorCode.INSUFFICIENT_BALANCE, "Available balance is lower than the amount");
    }

    @PostMapping("/probe/amount")
    String amount(@Valid @RequestBody AmountRequest request) {
      return "ok";
    }

    @GetMapping("/probe/authorization-denied")
    String authorizationDenied() {
      throw new AuthorizationDeniedException("secret-detail");
    }

    @GetMapping("/probe/access-denied")
    String accessDenied() {
      throw new AccessDeniedException("secret-detail");
    }

    @GetMapping("/probe/no-credentials")
    String noCredentials() {
      throw new AuthenticationCredentialsNotFoundException("secret-detail");
    }

    @GetMapping("/probe/header")
    String header(@RequestHeader("Idempotency-Key") UUID key) {
      return "ok";
    }

    @GetMapping("/probe/param")
    String param(@RequestParam @Positive int count) {
      return "ok";
    }

    @GetMapping("/probe/cookie")
    String cookie(@CookieValue("probe_cookie") String value) {
      return "ok";
    }

    @GetMapping("/probe/typed/{id}")
    String typed(@PathVariable UUID id) {
      return "ok";
    }

    @GetMapping("/probe/boom")
    String boom() {
      throw new IllegalStateException("secret-detail");
    }
  }

  record AmountRequest(@Positive BigDecimal amount) {}
}
