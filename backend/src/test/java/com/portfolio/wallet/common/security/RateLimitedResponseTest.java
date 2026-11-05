package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.error.RateLimitedException;
import com.portfolio.wallet.common.web.CorrelationIdFilter;
import com.portfolio.wallet.common.web.GlobalExceptionHandler;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link RateLimitedException} from a service (the database-backed lookup limit, MVP-ADR-20) is
 * answered exactly like the request filter's 429 (doc 06 MVP-ADR-19): same status, body fields,
 * message and {@code Retry-After}; only {@code timestamp} differs. A client cannot tell which limit
 * refused it.
 */
class RateLimitedResponseTest {

  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ProbeController())
          .setControllerAdvice(
              new GlobalExceptionHandler(
                  new StaticListableBeanFactory().getBeanProvider(AccessDenialAuditor.class)))
          .addFilters(new CorrelationIdFilter())
          .build();

  private final JsonSecurityErrorHandler filterErrors =
      new JsonSecurityErrorHandler(
          JSON, new StaticListableBeanFactory().getBeanProvider(AccessDenialAuditor.class));

  /** What the request filter writes, with the same correlation id in scope. */
  private MockHttpServletResponse filterResponse(long retryAfter, String correlationId)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MDC.put(CorrelationId.MDC_KEY, correlationId);
    try {
      filterErrors.tooManyRequests(response, retryAfter);
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
    return response;
  }

  private static Map<String, String> withoutTimestamp(String body) throws Exception {
    JsonNode node = JSON.readTree(body);
    Map<String, String> fields = new TreeMap<>();
    for (String name : node.propertyNames()) {
      if (!name.equals("timestamp")) {
        fields.put(name, node.get(name).toString());
      }
    }
    return fields;
  }

  @ParameterizedTest
  @CsvSource({"7, 7", "60, 60"})
  void theServiceLimitAnswersExactlyLikeTheFilter(long retryAfter, String header) throws Exception {
    MockHttpServletResponse fromService =
        mvc.perform(get("/probe/limited/" + retryAfter).header(CorrelationId.HEADER, "corr-429"))
            .andReturn()
            .getResponse();
    MockHttpServletResponse fromFilter = filterResponse(retryAfter, "corr-429");

    assertThat(fromService.getStatus()).isEqualTo(429).isEqualTo(fromFilter.getStatus());
    assertThat(fromService.getHeader(HttpHeaders.RETRY_AFTER))
        .isEqualTo(header)
        .isEqualTo(fromFilter.getHeader(HttpHeaders.RETRY_AFTER));
    assertThat(MediaType.parseMediaType(fromService.getContentType()))
        .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON));
    assertThat(MediaType.parseMediaType(fromFilter.getContentType()))
        .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON));
    assertThat(withoutTimestamp(fromService.getContentAsString()))
        .isEqualTo(withoutTimestamp(fromFilter.getContentAsString()));
    JsonNode body = JSON.readTree(fromService.getContentAsString());
    assertThat(body.get("success").asBoolean()).isFalse();
    assertThat(body.get("errorCode").asString()).isEqualTo("RATE_LIMITED");
    assertThat(body.get("correlationId").asString()).isEqualTo("corr-429");
    assertThat(body.get("timestamp").asString()).isNotBlank();
  }

  /** Doc 02 §2.3 lookup: {@code Retry-After} is at least 1, also for a wait computed as 0. */
  @Test
  void aZeroOrNegativeWaitIsSentAsOneSecond() throws Exception {
    for (long wait : new long[] {0, -5}) {
      MockHttpServletResponse response =
          mvc.perform(get("/probe/limited/" + wait)).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }
  }

  @RestController
  static class ProbeController {

    @GetMapping("/probe/limited/{seconds}")
    String limited(@PathVariable long seconds) {
      throw new RateLimitedException(seconds);
    }
  }
}
