package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.wallet.application.MoneyMovementService;
import com.portfolio.wallet.wallet.application.WalletQueryService;
import com.portfolio.wallet.wallet.application.WalletStatusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own wallet (documentations/02 §2.3). The customer is the JWT subject; no endpoint
 * takes a wallet or customer id, so another customer's wallet cannot be named. {@code
 * SecurityConfig} admits only {@code CUSTOMER} here; the annotation is the second layer. Before KYC
 * approval there is no wallet: {@code 404 WALLET_NOT_FOUND} on the reads and {@code 403
 * KYC_REQUIRED} on the money endpoints (MVP-ADR-10).
 *
 * <p>There is no customer unfreeze: only staff unfreeze (doc 08 §4B.2 requirement 6). Responses are
 * never cached: Spring Security's default {@code Cache-Control: no-store} applies.
 *
 * <p>Deposits and withdrawals require the {@code Idempotency-Key} header (doc 02 §1.3, §1.4) and
 * are limited to 30 per minute per customer (doc 02 §1.8; {@code SecurityConfig}). A replay returns
 * the original {@code 201} and body.
 */
@RestController
@RequestMapping("/api/v1/wallet")
@PreAuthorize("hasRole('CUSTOMER')")
public class WalletController {

  private final WalletQueryService queries;
  private final WalletStatusService statuses;
  private final MoneyMovementService movements;

  public WalletController(
      WalletQueryService queries, WalletStatusService statuses, MoneyMovementService movements) {
    this.queries = queries;
    this.statuses = statuses;
    this.movements = movements;
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

  /** Simulated deposit (MVP-ADR-13). */
  @PostMapping("/deposits")
  @ResponseStatus(HttpStatus.CREATED)
  MoneyMovementResponse deposit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
      @Valid @RequestBody DepositRequest body,
      HttpServletRequest request) {
    UUID key = IdempotencyKeyHeader.parse(idempotencyKey);
    return MoneyMovementResponse.from(
        movements.deposit(
            subject(jwt),
            key,
            body.amount(),
            body.currency(),
            body.reference(),
            RequestText.clientIp(request)));
  }

  /** Simulated withdrawal (MVP-ADR-13). */
  @PostMapping("/withdrawals")
  @ResponseStatus(HttpStatus.CREATED)
  MoneyMovementResponse withdraw(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
      @Valid @RequestBody WithdrawalRequest body,
      HttpServletRequest request) {
    UUID key = IdempotencyKeyHeader.parse(idempotencyKey);
    return MoneyMovementResponse.from(
        movements.withdraw(
            subject(jwt), key, body.amount(), body.currency(), RequestText.clientIp(request)));
  }

  private static UUID subject(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
