package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.application.CustomerProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own profile (documentations/02 §2.2). The customer is the JWT subject; no endpoint
 * takes a customer id.
 */
@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerController {

  private final CustomerProfileService profiles;

  public CustomerController(CustomerProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  CustomerProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
    return CustomerProfileResponse.from(profiles.profile(subject(jwt)));
  }

  @PutMapping
  CustomerProfileResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody UpdateProfileRequest body,
      HttpServletRequest request) {
    return CustomerProfileResponse.from(
        profiles.updateProfile(
            subject(jwt), body.dateOfBirth(), body.countryCode(), RequestText.clientIp(request)));
  }

  static UUID subject(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
