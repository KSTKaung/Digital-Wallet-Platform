package com.portfolio.wallet.user.web;

import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.web.ApiError;
import com.portfolio.wallet.common.web.OpenApiDocumentation;
import com.portfolio.wallet.user.application.CustomerProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Customer", description = "The caller's own profile")
@SecurityRequirement(name = OpenApiDocumentation.BEARER)
@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerController {

  private final CustomerProfileService profiles;

  public CustomerController(CustomerProfileService profiles) {
    this.profiles = profiles;
  }

  @Operation(operationId = "getCustomerProfile", summary = "Get the caller's profile")
  @GetMapping
  CustomerProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
    return CustomerProfileResponse.from(profiles.profile(subject(jwt)));
  }

  @Operation(
      operationId = "updateCustomerProfile",
      summary = "Set the date of birth and the country")
  @ApiError(
      code = ErrorCode.VALIDATION_ERROR,
      when = "a date of birth in the future, or a country code that is not ISO 3166-1 alpha-2")
  @ApiError(
      code = ErrorCode.INVALID_KYC_STATE,
      when = "locked from KYC submit onward (pending or approved application, or a legal name set)")
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
