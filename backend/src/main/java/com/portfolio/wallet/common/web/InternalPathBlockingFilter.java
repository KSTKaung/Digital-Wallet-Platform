package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MVP has no internal network APIs (documentations/06 MVP-ADR-04). Any {@code /internal/**}
 * request is answered with 404 before security or MVC, so such a path can never be exposed by
 * accident.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InternalPathBlockingFilter extends OncePerRequestFilter {

  private final JsonMapper jsonMapper;

  public InternalPathBlockingFilter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Decoded and cleaned ("%69nternal", ";params", "//"), so an encoded path cannot slip past.
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    return !(path.equals("/internal") || path.startsWith("/internal/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setStatus(ErrorCode.NOT_FOUND.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    jsonMapper.writeValue(
        response.getOutputStream(),
        ApiErrorResponse.of(
            ErrorCode.NOT_FOUND, "Resource not found", CorrelationId.current().orElse(null)));
  }
}
