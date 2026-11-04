package com.portfolio.securityfixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints with no {@code @PreAuthorize}, one in each role group of {@code
 * SecurityConfig}'s URL rules, so a test can prove that the URL layer alone refuses the wrong role
 * (owner decision A, PR 4a). {@link #OTHER_API} is under {@code /api/v1} but in no group, so the
 * {@code CUSTOMER} fallback applies (J3). Every probe answers {@code GET} only, so another method
 * on the same path shows that a method mismatch still counts as an endpoint. Outside the
 * application's root package, so only tests that import it get it.
 */
@RestController
public class UrlRuleProbeController {

  public static final String ADMIN_CUSTOMERS = "/api/v1/admin/customers/test-probe";
  public static final String ADMIN_KYC = "/api/v1/admin/kyc/test-probe";
  public static final String ADMIN_OTHER = "/api/v1/admin/test-probe";
  public static final String CUSTOMER_KYC = "/api/v1/kyc/test-probe";
  public static final String CUSTOMER_WALLET = "/api/v1/wallet/test-probe";
  public static final String OTHER_API = "/api/v1/test-probe-other";

  @GetMapping({ADMIN_CUSTOMERS, ADMIN_KYC, ADMIN_OTHER, CUSTOMER_KYC, CUSTOMER_WALLET, OTHER_API})
  String probe() {
    return "ok";
  }
}
