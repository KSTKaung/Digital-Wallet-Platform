package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.common.web.ApiErrorController;
import com.portfolio.wallet.support.RejectingJwtDecoder;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The limiter in the real filter chain, with small limits. Each test uses its own key. */
@WebMvcTest(
    controllers = ApiErrorController.class,
    properties = {
      "app.rate-limit.auth-requests-per-minute-per-ip=3",
      "app.rate-limit.other-requests-per-minute-per-user=2"
    })
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class, RejectingJwtDecoder.class})
class RateLimitWebTest {

  private static final String LOGIN = "/api/v1/auth/login";

  @Autowired MockMvc mvc;

  private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }

  private static MockHttpServletRequestBuilder from(String ip, MockHttpServletRequestBuilder base) {
    return base.with(
        request -> {
          request.setRemoteAddr(ip);
          return request;
        });
  }

  private static void assertLimited(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    assertThat(response.getContentAsString())
        .contains("\"success\":false")
        .contains("\"errorCode\":\"RATE_LIMITED\"")
        .contains("\"correlationId\"")
        .contains("\"timestamp\"");
  }

  @Test
  void anIpv6Slash64CountsAsOneClientAndAMappedAddressAsItsIpv4() throws Exception {
    for (int i = 1; i <= 3; i++) {
      assertThat(send(from("2001:db8:7:7::" + i, post(LOGIN))).getStatus()).isNotEqualTo(429);
    }
    assertLimited(send(from("2001:db8:7:7:ffff::1", post(LOGIN))));
    assertThat(send(from("2001:db8:7:8::1", post(LOGIN))).getStatus()).isNotEqualTo(429);

    for (int i = 0; i < 3; i++) {
      assertThat(send(from("192.0.2.77", post(LOGIN))).getStatus()).isNotEqualTo(429);
    }
    assertLimited(send(from("::ffff:192.0.2.77", post(LOGIN))));
  }

  @Test
  void authPathsAreLimitedPerIpAndAnotherIpIsNotAffected() throws Exception {
    for (int i = 0; i < 3; i++) {
      assertThat(send(from("192.0.2.1", post(LOGIN))).getStatus()).isNotEqualTo(429);
    }
    assertLimited(send(from("192.0.2.1", post(LOGIN))));
    assertThat(send(from("192.0.2.2", post(LOGIN))).getStatus()).isNotEqualTo(429);
  }

  @Test
  void everyAuthPathSharesOneCount() throws Exception {
    send(from("192.0.2.3", post(LOGIN)));
    send(from("192.0.2.3", post("/api/v1/auth/register")));
    send(from("192.0.2.3", post("/api/v1/auth/change-password")));
    assertLimited(send(from("192.0.2.3", post("/api/v1/auth/forgot-password"))));
  }

  @Test
  void anEncodedPathIsCountedToo() throws Exception {
    for (int i = 0; i < 3; i++) {
      send(from("192.0.2.4", post(URI.create("http://localhost/api/v1/%61uth/login"))));
    }
    assertLimited(send(from("192.0.2.4", post(LOGIN))));
  }

  @Test
  void theLimitRunsBeforeTheCsrfCheck() throws Exception {
    String refresh = "/api/v1/auth/refresh";
    for (int i = 0; i < 3; i++) {
      assertThat(send(from("192.0.2.5", post(refresh))).getStatus()).isEqualTo(403);
    }
    assertLimited(send(from("192.0.2.5", post(refresh))));
  }

  @Test
  void aSpoofedForwardedHeaderDoesNotChangeTheKey() throws Exception {
    for (int i = 0; i < 3; i++) {
      send(from("192.0.2.6", post(LOGIN)).header("X-Forwarded-For", "203.0.113." + i));
    }
    assertLimited(send(from("192.0.2.6", post(LOGIN)).header("X-Forwarded-For", "203.0.113.200")));
  }

  @Test
  void otherPathsAreLimitedPerUserRegardlessOfIp() throws Exception {
    String path = "/api/v1/no-such-thing";
    assertThat(send(from("192.0.2.7", get(path)).with(user("alice"))).getStatus()).isEqualTo(404);
    assertThat(send(from("192.0.2.8", get(path)).with(user("alice"))).getStatus()).isEqualTo(404);
    assertLimited(send(from("192.0.2.9", get(path)).with(user("alice"))));
    assertThat(send(get(path).with(user("bob"))).getStatus()).isEqualTo(404);
  }

  @Test
  void anonymousRequestsToOtherPathsAreJust401() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertThat(send(get("/api/v1/wallet")).getStatus()).isEqualTo(401);
    }
  }

  @Test
  void healthIsExemptEvenForAUserOverTheLimit() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertThat(send(get("/actuator/health").with(user("carol"))).getStatus()).isNotEqualTo(429);
    }
  }

  @Test
  void preflightsAreNotCounted() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertThat(
              send(from(
                      "192.0.2.10",
                      options(LOGIN)
                          .header("Origin", "http://localhost:5173")
                          .header("Access-Control-Request-Method", "POST")))
                  .getStatus())
          .isEqualTo(200);
    }
    assertThat(send(from("192.0.2.10", post(LOGIN))).getStatus()).isNotEqualTo(429);
  }
}
