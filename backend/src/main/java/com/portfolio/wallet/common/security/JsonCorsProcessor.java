package com.portfolio.wallet.common.security;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * Spring's CORS rules, with the documented error body (documentations/02 §1.7) instead of the
 * plain-text 403 when a request's {@code Origin} or preflight is refused. Logged only, with no
 * header values: see {@link JsonSecurityErrorHandler}.
 */
class JsonCorsProcessor extends DefaultCorsProcessor {

  private static final Logger log = LoggerFactory.getLogger(JsonCorsProcessor.class);

  private final JsonSecurityErrorHandler errors;

  JsonCorsProcessor(JsonSecurityErrorHandler errors) {
    this.errors = errors;
  }

  @Override
  protected void rejectRequest(ServerHttpResponse response) throws IOException {
    log.info("Refused a cross-origin request");
    errors.forbidden(((ServletServerHttpResponse) response).getServletResponse());
  }
}
