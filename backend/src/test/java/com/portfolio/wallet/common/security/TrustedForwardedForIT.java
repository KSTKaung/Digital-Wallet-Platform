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

/** With the test client as the trusted proxy, its header gives the key and the audited address. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.rate-limit.auth-requests-per-minute-per-ip=2",
      "app.auth.purge.enabled=false",
      "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1"
    })
class TrustedForwardedForIT {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.registerWithRealLimits(registry);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private final HttpClient client = HttpClient.newHttpClient();

  private int forgot(String forwardedFor, String correlationId) throws Exception {
    return client
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/auth/forgot-password"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .header(CorrelationId.HEADER, correlationId)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"user-" + UUID.randomUUID() + "@example.test\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  @Test
  void aTrustedProxysHeaderGivesTheKeyAndTheAuditedAddress() throws Exception {
    String correlationId = UUID.randomUUID().toString();
    assertThat(forgot("203.0.113.7", correlationId)).isEqualTo(202);
    assertThat(
            jdbc.queryForObject(
                "SELECT host(client_ip) FROM audit.audit_logs WHERE correlation_id = ?"
                    + " AND action = 'PASSWORD_RESET_REQUEST'",
                String.class,
                correlationId))
        .isEqualTo("203.0.113.7");

    assertThat(forgot("203.0.113.7", correlationId)).isEqualTo(202);
    assertThat(forgot("203.0.113.7", correlationId)).isEqualTo(429);
    assertThat(forgot("203.0.113.8", correlationId)).isEqualTo(202);
  }
}
