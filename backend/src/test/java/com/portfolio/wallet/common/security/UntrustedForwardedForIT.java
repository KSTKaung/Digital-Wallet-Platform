package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.support.PostgresTestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Over real HTTP, because only Tomcat applies the proxy rules. By default nobody is trusted, so a
 * spoofed header changes neither the limiter's key nor the audited address.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.rate-limit.auth-requests-per-minute-per-ip=3",
      "app.auth.purge.enabled=false"
    })
class UntrustedForwardedForIT {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.registerWithRealLimits(registry);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private final HttpClient client = HttpClient.newHttpClient();

  private HttpResponse<String> forgot(String forwardedFor, String correlationId) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/auth/forgot-password"))
            .header("Content-Type", "application/json")
            .header("X-Forwarded-For", forwardedFor)
            .header(CorrelationId.HEADER, correlationId)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\"user-" + UUID.randomUUID() + "@example.test\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aSpoofedHeaderChangesNeitherTheKeyNorTheAuditedAddress() throws Exception {
    String correlationId = UUID.randomUUID().toString();
    assertThat(forgot("203.0.113.1", correlationId).statusCode()).isEqualTo(202);
    assertThat(
            jdbc.queryForObject(
                "SELECT host(client_ip) FROM audit.audit_logs WHERE correlation_id = ?"
                    + " AND action = 'PASSWORD_RESET_REQUEST'",
                String.class,
                correlationId))
        .isEqualTo("127.0.0.1");

    assertThat(forgot("203.0.113.2", correlationId).statusCode()).isEqualTo(202);
    assertThat(forgot("203.0.113.3", correlationId).statusCode()).isEqualTo(202);

    HttpResponse<String> limited = forgot("203.0.113.4", correlationId);
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    assertThat(limited.body()).contains("\"errorCode\":\"RATE_LIMITED\"");
  }
}
