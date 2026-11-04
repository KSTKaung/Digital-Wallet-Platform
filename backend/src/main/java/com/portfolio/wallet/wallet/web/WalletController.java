package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.wallet.application.WalletQueryService;
import com.portfolio.wallet.wallet.application.WalletStatusService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own wallet (documentations/02 §2.3). The customer is the JWT subject; no endpoint
 * takes a wallet or customer id, so another customer's wallet cannot be named. {@code
 * SecurityConfig} admits only {@code CUSTOMER} here; the annotation is the second layer. Before KYC
 * approval there is no wallet: {@code 404 WALLET_NOT_FOUND} (MVP-ADR-10).
 *
 * <p>There is no customer unfreeze: only staff unfreeze (doc 08 §4B.2 requirement 6). Responses are
 * never cached: Spring Security's default {@code Cache-Control: no-store} applies.
 */
@RestController
@RequestMapping("/api/v1/wallet")
@PreAuthorize("hasRole('CUSTOMER')")
public class WalletController {

  private final WalletQueryService queries;
  private final WalletStatusService statuses;

  public WalletController(WalletQueryService queries, WalletStatusService statuses) {
    this.queries = queries;
    this.statuses = statuses;
  }

  @GetMapping
  WalletResponse wallet(@AuthenticationPrincipal Jwt jwt) {
    return WalletResponse.from(queries.wallet(subject(jwt)));
  }

  @GetMapping("/balance")
  WalletBalanceResponse balance(@AuthenticationPrincipal Jwt jwt) {
    return WalletBalanceResponse.from(queries.balance(subject(jwt)));
  }

  @GetMapping("/status")
  WalletStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
    return WalletStatusResponse.from(queries.status(subject(jwt)));
  }

  @GetMapping("/limits")
  WalletLimitsResponse limits(@AuthenticationPrincipal Jwt jwt) {
    return WalletLimitsResponse.from(queries.limits(subject(jwt)));
  }

  /** Customer self-service freeze; audited. Freezing a frozen wallet changes nothing. */
  @PostMapping("/freeze")
  WalletStatusResponse freeze(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return WalletStatusResponse.from(
        statuses.freezeOwn(subject(jwt), RequestText.clientIp(request)));
  }

  private static UUID subject(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
