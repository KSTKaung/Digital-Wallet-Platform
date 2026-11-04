package com.portfolio.securityfixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only protected endpoints. Outside the application's root package, so only tests that import
 * it get it: no other context can pick it up by scanning.
 *
 * <p>Under {@code /api} (CORS applies) but outside {@code /api/v1}, whose paths without a rule of
 * their own require {@code CUSTOMER} (J3): these probes test authentication and the method security
 * annotations alone, not the URL rules.
 */
@RestController
public class RoleProbeController {

  public static final String ANY = "/api/test-probe/any";
  public static final String CUSTOMER = "/api/test-probe/customer";
  public static final String ADMIN = "/api/test-probe/admin";

  @GetMapping(ANY)
  String any() {
    return "ok";
  }

  @GetMapping(CUSTOMER)
  @PreAuthorize("hasRole('CUSTOMER')")
  String customer() {
    return "ok";
  }

  @GetMapping(ADMIN)
  @PreAuthorize("hasRole('ADMIN')")
  String admin() {
    return "ok";
  }
}
