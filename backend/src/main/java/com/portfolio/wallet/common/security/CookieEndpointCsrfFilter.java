package com.portfolio.wallet.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF defence for the cookie-authenticated endpoints, on top of {@code SameSite=Strict}
 * (documentations/06 MVP-ADR-14): a request needs {@code X-Requested-With} and an {@code Origin} on
 * the CORS allow-list. A request without {@code Origin} is refused: browsers send it on every POST,
 * so only non-browser clients lack it, and the SPA is the only intended caller.
 *
 * <p>Not a Spring bean, so that the servlet container does not register it a second time outside
 * the security chain.
 */
public class CookieEndpointCsrfFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(CookieEndpointCsrfFilter.class);
  private static final String REQUESTED_WITH = "X-Requested-With";
  private static final String ORIGIN = "Origin";

  private final RequestMatcher protectedPaths;
  private final CorsProperties cors;
  private final JsonSecurityErrorHandler errors;

  public CookieEndpointCsrfFilter(
      RequestMatcher protectedPaths, CorsProperties cors, JsonSecurityErrorHandler errors) {
    this.protectedPaths = protectedPaths;
    this.cors = cors;
    this.errors = errors;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !protectedPaths.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestedWith = request.getHeader(REQUESTED_WITH);
    String origin = request.getHeader(ORIGIN);
    if (requestedWith == null
        || requestedWith.isBlank()
        || origin == null
        || !cors.allowedOrigins().contains(origin)) {
      // Header values are attacker-controlled and stay out of the log.
      log.warn("Refused a cookie-endpoint request by the CSRF check");
      errors.csrfRefused(request, response);
      return;
    }
    chain.doFilter(request, response);
  }
}
