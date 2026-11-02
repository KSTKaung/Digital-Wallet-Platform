package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.correlation.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a valid {@code X-Correlation-Id} or generates one, exposes it in the MDC for logs and
 * echoes it on the response (documentations/02 §1.3). Runs before Spring Security so that 401/403
 * responses carry it too.
 *
 * <p>It also runs on the container's ERROR dispatch, because the first pass has already cleared the
 * MDC by then: {@code /error} reuses the id of the original request, so the error body and the
 * response header agree even when the id was generated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = (String) request.getAttribute(REQUEST_ATTRIBUTE);
    if (correlationId == null) {
      String incoming = request.getHeader(CorrelationId.HEADER);
      correlationId = CorrelationId.isValid(incoming) ? incoming : UUID.randomUUID().toString();
      request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
    }

    MDC.put(CorrelationId.MDC_KEY, correlationId);
    response.setHeader(CorrelationId.HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }
}
