package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.application.KycApplicationCommand;
import com.portfolio.wallet.user.application.KycApplicationService;
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
@RestController
@RequestMapping("/api/v1/kyc")
@PreAuthorize("hasRole('CUSTOMER')")
public class KycController {

  private static final String APPLICATIONS = "/api/v1/kyc/applications/";

  private final KycApplicationService kyc;

  public KycController(KycApplicationService kyc) {
    this.kyc = kyc;
  }

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

  @GetMapping("/applications/{id}")
  KycApplicationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return KycApplicationResponse.from(kyc.get(CustomerController.subject(jwt), id));
  }

  @PostMapping("/applications/{id}/submit")
  KycApplicationResponse submit(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
    return KycApplicationResponse.from(
        kyc.submit(CustomerController.subject(jwt), id, RequestText.clientIp(request)));
  }

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
