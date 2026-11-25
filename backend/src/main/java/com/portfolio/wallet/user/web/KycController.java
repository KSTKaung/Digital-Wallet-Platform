package com.portfolio.wallet.user.web;

import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.web.ApiError;
import com.portfolio.wallet.common.web.OpenApiDocumentation;
import com.portfolio.wallet.user.application.KycApplicationCommand;
import com.portfolio.wallet.user.application.KycApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KYC for the customer (documentations/02 §2.2; doc 06 MVP-ADR-10 amendment). Owner only: another
 * customer's application id gives the same 404 as a missing one (MVP-ADR-19).
 */
@Tag(name = "KYC", description = "The caller's KYC applications")
@SecurityRequirement(name = OpenApiDocumentation.BEARER)
@RestController
@RequestMapping("/api/v1/kyc")
@PreAuthorize("hasRole('CUSTOMER')")
public class KycController {

  private static final String APPLICATIONS = "/api/v1/kyc/applications/";

  private final KycApplicationService kyc;

  public KycController(KycApplicationService kyc) {
    this.kyc = kyc;
  }

  @Operation(operationId = "createKycApplication", summary = "Start a KYC application (DRAFT)")
  @ApiResponse(
      responseCode = "201",
      description = "The new DRAFT application",
      headers =
          @Header(
              name = "Location",
              description = "The path of the new application",
              schema = @Schema(type = "string")),
      content = @Content(schema = @Schema(implementation = KycApplicationResponse.class)))
  @ApiError(
      code = ErrorCode.KYC_ALREADY_ACTIVE,
      when = "the caller already has a draft, pending or approved application")
  @PostMapping("/applications")
  ResponseEntity<KycApplicationResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody KycApplicationRequest body,
      HttpServletRequest request) {
    KycApplicationResponse created =
        KycApplicationResponse.from(
            kyc.create(
                CustomerController.subject(jwt), command(body), RequestText.clientIp(request)));
    return ResponseEntity.created(URI.create(APPLICATIONS + created.id())).body(created);
  }

  @Operation(operationId = "updateKycApplication", summary = "Edit a DRAFT application")
  @ApiError(code = ErrorCode.INVALID_KYC_STATE, when = "the application is no longer a DRAFT")
  @PutMapping("/applications/{id}")
  KycApplicationResponse edit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody KycApplicationRequest body,
      HttpServletRequest request) {
    return KycApplicationResponse.from(
        kyc.edit(
            CustomerController.subject(jwt), id, command(body), RequestText.clientIp(request)));
  }

  @Operation(operationId = "getKycApplication", summary = "Get one of the caller's applications")
  @GetMapping("/applications/{id}")
  KycApplicationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return KycApplicationResponse.from(kyc.get(CustomerController.subject(jwt), id));
  }

  @Operation(
      operationId = "submitKycApplication",
      summary = "Submit a DRAFT application for review")
  @ApiError(
      code = ErrorCode.VALIDATION_ERROR,
      when = "the profile has no date of birth or no country code yet")
  @ApiError(code = ErrorCode.INVALID_KYC_STATE, when = "the application is not a DRAFT")
  @PostMapping("/applications/{id}/submit")
  KycApplicationResponse submit(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
    return KycApplicationResponse.from(
        kyc.submit(CustomerController.subject(jwt), id, RequestText.clientIp(request)));
  }

  @Operation(operationId = "getKycStatus", summary = "Get the caller's KYC status")
  @GetMapping("/status")
  KycStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
    return KycStatusResponse.from(kyc.status(CustomerController.subject(jwt)));
  }

  private static KycApplicationCommand command(KycApplicationRequest body) {
    return new KycApplicationCommand(
        body.verificationLevel(),
        body.documentType(),
        body.documentReference(),
        body.firstName(),
        body.lastName());
  }
}
