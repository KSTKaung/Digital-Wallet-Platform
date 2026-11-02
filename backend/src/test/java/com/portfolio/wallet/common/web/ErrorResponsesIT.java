package com.portfolio.wallet.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.support.PostgresTestDatabase;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Errors raised before MVC, over real HTTP: only a servlet container performs the ERROR dispatch
 * that reaches {@link ApiErrorController} (documentations/06 MVP-ADR-19).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorResponsesIT {

  private static final Set<String> DOCUMENTED_FIELDS =
      Set.of("success", "errorCode", "message", "correlationId", "timestamp");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.register(registry);
  }

  @TestConfiguration
  static class FailingFilterConfig {
    @Bean
    FilterRegistrationBean<Filter> failingFilter() {
      Filter failing =
          (request, response, chain) -> {
            throw new ServletException("secret-detail");
          };
      FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(failing);
      registration.addUrlPatterns("/probe-filter-failure");
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
      return registration;
    }
  }

  @LocalServerPort int port;
  @Autowired JsonMapper jsonMapper;

  private final HttpClient client = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path, String correlationId)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (correlationId != null) {
      request.header(CorrelationId.HEADER, correlationId);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertDocumentedBody(HttpResponse<String> response, int status, String errorCode) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
    JsonNode body = jsonMapper.readTree(response.body());
    assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(DOCUMENTED_FIELDS);
    assertThat(body.get("success").asBoolean()).isFalse();
    assertThat(body.get("errorCode").asString()).isEqualTo(errorCode);
    assertThat(response.body()).doesNotContain("secret-detail", "Exception", "probe-filter");
  }

  @Test
  void firewallRejectionHasTheDocumentedBody() throws Exception {
    HttpResponse<String> response = get("/api/v1/wallet;x=y", "firewall-1");

    assertDocumentedBody(response, 400, "VALIDATION_ERROR");
    assertThat(jsonMapper.readTree(response.body()).get("correlationId").asString())
        .isEqualTo("firewall-1");
  }

  @Test
  void exceptionInAFilterHasTheDocumentedBodyWithoutDetail() throws Exception {
    HttpResponse<String> response = get("/probe-filter-failure", "filter-1");

    assertDocumentedBody(response, 500, "SYSTEM_ERROR");
    assertThat(jsonMapper.readTree(response.body()).get("correlationId").asString())
        .isEqualTo("filter-1");
  }

  /** The id generated in the first pass, already on the response, must also reach the body. */
  @Test
  void generatedCorrelationIdIsTheSameInHeaderAndBody() throws Exception {
    HttpResponse<String> response = get("/probe-filter-failure", null);

    assertThat(response.headers().firstValue(CorrelationId.HEADER))
        .hasValue(jsonMapper.readTree(response.body()).get("correlationId").asString());
  }
}
