package com.portfolio.securityfixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only protected endpoints. Outside the application's root package, so only tests that import
 * it get it: no other context can pick it up by scanning.
 */
@RestController
public class RoleProbeController {

  public static final String ANY = "/api/v1/test-probe/any";
  public static final String CUSTOMER = "/api/v1/test-probe/customer";
  public static final String ADMIN = "/api/v1/test-probe/admin";

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
