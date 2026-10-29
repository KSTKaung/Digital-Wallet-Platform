package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.json.JsonMapper;

/** 401 and 403 bodies use the documented error format with the U9 codes. */
class JsonSecurityErrorHandlerTest {

  private final JsonSecurityErrorHandler handler =
      new JsonSecurityErrorHandler(JsonMapper.builder().findAndAddModules().build());

  @Test
  void unauthenticatedIs401Unauthorized() throws Exception {
    var response = new MockHttpServletResponse();

    handler.commence(
        new MockHttpServletRequest(), response, new BadCredentialsException("bad token detail"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString())
        .contains("\"success\":false", "\"errorCode\":\"UNAUTHORIZED\"")
        .doesNotContain("bad token detail");
  }

  @Test
  void accessDeniedIs403Forbidden() throws Exception {
    var response = new MockHttpServletResponse();

    handler.handle(
        new MockHttpServletRequest(), response, new AccessDeniedException("role ADMIN required"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString())
        .contains("\"errorCode\":\"FORBIDDEN\"")
        .doesNotContain("role ADMIN required");
  }
}
