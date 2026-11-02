package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class CookieEndpointCsrfFilterTest {

  private static final String ALLOWED = "https://app.example";

  private final CookieEndpointCsrfFilter filter =
      new CookieEndpointCsrfFilter(
          request -> request.getRequestURI().endsWith("/refresh"),
          new CorsProperties(List.of(ALLOWED)),
          new JsonSecurityErrorHandler(
              JsonMapper.builder().findAndAddModules().build(),
              new StaticListableBeanFactory().getBeanProvider(AccessDenialAuditor.class)));

  private int statusOf(String path, String origin, String requestedWith) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    if (origin != null) {
      request.addHeader("Origin", origin);
    }
    if (requestedWith != null) {
      request.addHeader("X-Requested-With", requestedWith);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response.getStatus();
  }

  @Test
  void otherPathsAreNotChecked() throws Exception {
    assertThat(statusOf("/api/v1/auth/login", null, null)).isEqualTo(200);
  }

  @Test
  void anAllowedOriginWithTheHeaderPasses() throws Exception {
    assertThat(statusOf("/refresh", ALLOWED, "XMLHttpRequest")).isEqualTo(200);
  }

  @Test
  void everythingElseIsRefused() throws Exception {
    assertThat(statusOf("/refresh", null, "XMLHttpRequest")).isEqualTo(403);
    assertThat(statusOf("/refresh", "null", "XMLHttpRequest")).isEqualTo(403);
    assertThat(statusOf("/refresh", "https://evil.example", "XMLHttpRequest")).isEqualTo(403);
    assertThat(statusOf("/refresh", ALLOWED, null)).isEqualTo(403);
    assertThat(statusOf("/refresh", ALLOWED, " ")).isEqualTo(403);
  }
}
