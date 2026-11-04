package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.wallet.application.WalletStatusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff freeze and unfreeze of a customer's wallet (documentations/02 §2.7; doc 06 MVP-ADR-10
 * amendment, MVP-ADR-12). {@code SecurityConfig} admits only {@code ADMIN} to {@code
 * /admin/customers/**} before any body is read; the annotation is the second layer. The staff
 * member is the JWT subject. An unknown customer and one without a wallet give the identical {@code
 * 404}.
 */
@RestController
@RequestMapping("/api/v1/admin/customers/{customerId}")
@PreAuthorize("hasRole('ADMIN')")
public class CustomerWalletController {

  private final WalletStatusService statuses;

  public CustomerWalletController(WalletStatusService statuses) {
    this.statuses = statuses;
  }

  @PostMapping("/freeze")
  CustomerWalletStatusResponse freeze(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID customerId,
      @Valid @RequestBody WalletStatusReasonRequest body,
      HttpServletRequest request) {
    return CustomerWalletStatusResponse.from(
        statuses.freezeByStaff(
            staff(jwt), customerId, body.reason(), RequestText.clientIp(request)));
  }

  @PostMapping("/unfreeze")
  CustomerWalletStatusResponse unfreeze(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID customerId,
      @Valid @RequestBody WalletStatusReasonRequest body,
      HttpServletRequest request) {
    return CustomerWalletStatusResponse.from(
        statuses.unfreezeByStaff(
            staff(jwt), customerId, body.reason(), RequestText.clientIp(request)));
  }

  private static UUID staff(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
