package com.portfolio.wallet.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reports a {@code 403} refusal to the audit log. The response is decided already, so a failure
 * here is logged by class only and goes no further.
 */
public final class AccessDenials {

  private static final Logger log = LoggerFactory.getLogger(AccessDenials.class);
  private static final String CUSTOMER_AUTHORITY = "ROLE_CUSTOMER";

  private AccessDenials() {}

  public static void report(
      ObjectProvider<AccessDenialAuditor> auditors,
      HttpServletRequest request,
      AccessDenial.Kind kind) {
    try {
      AccessDenialAuditor auditor = auditors.getIfAvailable();
      if (auditor != null) {
        auditor.record(denial(request, kind));
      }
    } catch (RuntimeException e) {
      log.warn("Could not audit a refusal: failure={}", e.getClass().getSimpleName());
    }
  }

  private static AccessDenial denial(HttpServletRequest request, AccessDenial.Kind kind) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean known =
        authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    return new AccessDenial(
        kind,
        known ? subject(authentication) : null,
        known && !hasCustomerRole(authentication),
        request.getMethod(),
        request.getRequestURI(),
        ClientAddresses.parse(request.getRemoteAddr()));
  }

  private static UUID subject(Authentication authentication) {
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean hasCustomerRole(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> CUSTOMER_AUTHORITY.equals(authority.getAuthority()));
  }
}
