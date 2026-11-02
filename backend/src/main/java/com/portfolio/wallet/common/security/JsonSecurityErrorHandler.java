package com.portfolio.wallet.common.security;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.web.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401 and 403 responses in the documented error format (documentations/02 §1.7). A 403 for
 * an authenticated request and a CSRF refusal are audited; a 401 and a foreign {@code Origin} are
 * logged only, because anyone can send those without limit and the audit table is append-only.
 */
@Component
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private static final Logger log = LoggerFactory.getLogger(JsonSecurityErrorHandler.class);

  private final JsonMapper jsonMapper;
  private final ObjectProvider<AccessDenialAuditor> auditors;

  public JsonSecurityErrorHandler(
      JsonMapper jsonMapper, ObjectProvider<AccessDenialAuditor> auditors) {
    this.jsonMapper = jsonMapper;
    this.auditors = auditors;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    log.info("Refused an unauthenticated request");
    write(response, ErrorCode.UNAUTHORIZED, "Authentication required");
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    AccessDenials.report(auditors, request, AccessDenial.Kind.ACCESS_DENIED);
    forbidden(response);
  }

  /** The CSRF filter's refusal. */
  void csrfRefused(HttpServletRequest request, HttpServletResponse response) throws IOException {
    AccessDenials.report(auditors, request, AccessDenial.Kind.CSRF_REFUSED);
    forbidden(response);
  }

  /** The same 403 for every refusal, whichever filter makes it. */
  void forbidden(HttpServletResponse response) throws IOException {
    write(response, ErrorCode.FORBIDDEN, "Access denied");
  }

  void tooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    write(response, ErrorCode.RATE_LIMITED, "Too many requests");
  }

  private void write(HttpServletResponse response, ErrorCode code, String message)
      throws IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    jsonMapper.writeValue(
        response.getOutputStream(),
        ApiErrorResponse.of(code, message, CorrelationId.current().orElse(null)));
  }
}
