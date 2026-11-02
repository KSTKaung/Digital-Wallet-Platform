package com.portfolio.wallet.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers {@code 429 RATE_LIMITED} with {@code Retry-After} once a key is over its limit
 * (documentations/02 §1.8; doc 06 MVP-ADR-06). A request with no key is not limited here.
 * Preflights pass, since a browser sends them by itself and they do no work.
 *
 * <p>Not a Spring bean, so that the servlet container does not register it a second time outside
 * the security chain.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private final String scope;
  private final RequestMatcher limitedPaths;
  private final Function<HttpServletRequest, String> keys;
  private final RateLimiter limiter;
  private final JsonSecurityErrorHandler errors;

  RateLimitFilter(
      String scope,
      RequestMatcher limitedPaths,
      Function<HttpServletRequest, String> keys,
      RateLimiter limiter,
      JsonSecurityErrorHandler errors) {
    this.scope = scope;
    this.limitedPaths = limitedPaths;
    this.keys = keys;
    this.limiter = limiter;
    this.errors = errors;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return HttpMethod.OPTIONS.matches(request.getMethod()) || !limitedPaths.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = keys.apply(request);
    if (key != null) {
      RateLimiter.Decision decision = limiter.tryAcquire(key);
      if (!decision.allowed()) {
        // The key is an address or a user id, so it stays out of the log.
        log.warn("Refused a request by the rate limit: scope={}", scope);
        errors.tooManyRequests(response, decision.retryAfterSeconds());
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
