package com.portfolio.wallet.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Error-body shape and mapping (documentations/02 §1.7), without a Spring context. */
class GlobalExceptionHandlerTest {

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ProbeController())
          .setControllerAdvice(new GlobalExceptionHandler())
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

    @GetMapping("/probe/boom")
    String boom() {
      throw new IllegalStateException("secret-detail");
    }
  }

  record AmountRequest(@Positive BigDecimal amount) {}
}
