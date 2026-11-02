package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** 401 and 403 bodies use the documented error format with the U9 codes; 403s are audited. */
class JsonSecurityErrorHandlerTest {

  private final AccessDenialAuditor auditor = mock(AccessDenialAuditor.class);
  private final JsonSecurityErrorHandler handler = handlerWith(auditor);

  private static JsonSecurityErrorHandler handlerWith(AccessDenialAuditor auditor) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("auditor", auditor);
    return new JsonSecurityErrorHandler(
        JsonMapper.builder().findAndAddModules().build(),
        beans.getBeanProvider(AccessDenialAuditor.class));
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/thing");
    request.setRemoteAddr("192.0.2.9");
    return request;
  }

  private AccessDenial recorded() {
    ArgumentCaptor<AccessDenial> denial = ArgumentCaptor.forClass(AccessDenial.class);
    verify(auditor, times(1)).record(denial.capture());
    return denial.getValue();
  }

  private static void authenticateAs(String subject, String... roles) {
    TestingAuthenticationToken token = new TestingAuthenticationToken(subject, "n/a", roles);
    SecurityContextHolder.getContext().setAuthentication(token);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void unauthenticatedIs401UnauthorizedAndIsNotAudited() throws Exception {
    var response = new MockHttpServletResponse();

    handler.commence(request(), response, new BadCredentialsException("bad token detail"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString())
        .contains("\"success\":false", "\"errorCode\":\"UNAUTHORIZED\"")
        .doesNotContain("bad token detail");
    verify(auditor, never()).record(any());
  }

  @Test
  void accessDeniedIs403ForbiddenAndAuditedOnceWithTheActor() throws Exception {
    UUID actor = UUID.randomUUID();
    authenticateAs(actor.toString(), "ROLE_CUSTOMER");
    var response = new MockHttpServletResponse();

    handler.handle(request(), response, new AccessDeniedException("role ADMIN required"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString())
        .contains("\"errorCode\":\"FORBIDDEN\"")
        .doesNotContain("role ADMIN required");
    AccessDenial denial = recorded();
    assertThat(denial.kind()).isEqualTo(AccessDenial.Kind.ACCESS_DENIED);
    assertThat(denial.actorId()).isEqualTo(actor);
    assertThat(denial.staff()).isFalse();
    assertThat(denial.method()).isEqualTo("GET");
    assertThat(denial.path()).isEqualTo("/api/v1/thing");
    assertThat(denial.clientIp().getHostAddress()).isEqualTo("192.0.2.9");
  }

  @Test
  void aUserWithoutTheCustomerRoleIsStaff() throws Exception {
    authenticateAs(UUID.randomUUID().toString(), "ROLE_ADMIN");

    handler.handle(request(), new MockHttpServletResponse(), new AccessDeniedException("x"));

    assertThat(recorded().staff()).isTrue();
  }

  @Test
  void aSubjectThatIsNoIdIsAnUnknownActor() throws Exception {
    authenticateAs("not-a-uuid", "ROLE_CUSTOMER");

    handler.handle(request(), new MockHttpServletResponse(), new AccessDeniedException("x"));

    assertThat(recorded().actorId()).isNull();
  }

  @Test
  void aCsrfRefusalIsAuditedOnceWithNoActor() throws Exception {
    var response = new MockHttpServletResponse();

    handler.csrfRefused(request(), response);

    assertThat(response.getStatus()).isEqualTo(403);
    AccessDenial denial = recorded();
    assertThat(denial.kind()).isEqualTo(AccessDenial.Kind.CSRF_REFUSED);
    assertThat(denial.actorId()).isNull();
  }

  /** The foreign-Origin 403: anyone can send it without limit, so it stays out of the table. */
  @Test
  void aPlainForbiddenIsNotAudited() throws Exception {
    var response = new MockHttpServletResponse();

    handler.forbidden(response);

    assertThat(response.getStatus()).isEqualTo(403);
    verify(auditor, never()).record(any());
  }

  @Test
  void aFailedAuditDoesNotChangeTheResponse() throws Exception {
    doThrow(new IllegalStateException("database down")).when(auditor).record(any());
    var denied = new MockHttpServletResponse();
    var csrf = new MockHttpServletResponse();

    handler.handle(request(), denied, new AccessDeniedException("x"));
    handler.csrfRefused(request(), csrf);

    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(denied.getContentAsString()).contains("\"errorCode\":\"FORBIDDEN\"");
    assertThat(csrf.getStatus()).isEqualTo(403);
  }

  @Test
  void withoutAnAuditorTheResponseIsTheSame() throws Exception {
    var response = new MockHttpServletResponse();

    new JsonSecurityErrorHandler(
            JsonMapper.builder().findAndAddModules().build(),
            new StaticListableBeanFactory().getBeanProvider(AccessDenialAuditor.class))
        .handle(request(), response, new AccessDeniedException("x"));

    assertThat(response.getStatus()).isEqualTo(403);
  }
}
