package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.web.ApiError;
import com.portfolio.wallet.common.web.OpenApiDocumentation;
import com.portfolio.wallet.wallet.application.MoneyMovementService;
import com.portfolio.wallet.wallet.application.WalletQueryService;
import com.portfolio.wallet.wallet.application.WalletStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Wallet", description = "The caller's own wallet, deposits and withdrawals")
@SecurityRequirement(name = OpenApiDocumentation.BEARER)
@RestController
@RequestMapping("/api/v1/wallet")
@PreAuthorize("hasRole('CUSTOMER')")
public class WalletController {

  // OpenAPI descriptions (documentations/02 §2.3), never a request value.
  private static final String NO_WALLET = "no wallet yet: KYC is not approved";
  private static final String MOVED =
      "COMPLETED and posted (ledgerStatus POSTED), returned after the commit; a replay with the"
          + " same Idempotency-Key and body returns the original status and body";
  private static final String INVALID_AMOUNT =
      "zero or negative, more than 2 decimals, too many digits, or below the minimum";
  private static final String NOT_THB = "a currency other than THB";
  private static final String KEY_REUSED =
      "the Idempotency-Key was used with a different request; a stored refusal is replayed with"
          + " its own code";
  private static final String OVER_SINGLE = "above the per-transaction maximum";
  private static final String OVER_DAILY = "over the daily limit of the Asia/Bangkok day";

  private final WalletQueryService queries;
  private final WalletStatusService statuses;
  private final MoneyMovementService movements;

  public WalletController(
      WalletQueryService queries, WalletStatusService statuses, MoneyMovementService movements) {
    this.queries = queries;
    this.statuses = statuses;
    this.movements = movements;
  }

  @Operation(operationId = "getWallet", summary = "Get the caller's wallet")
  @ApiError(code = ErrorCode.WALLET_NOT_FOUND, when = NO_WALLET)
  @GetMapping
  WalletResponse wallet(@AuthenticationPrincipal Jwt jwt) {
    return WalletResponse.from(queries.wallet(subject(jwt)));
  }

  @Operation(operationId = "getWalletBalance", summary = "Get the caller's balance")
  @ApiError(code = ErrorCode.WALLET_NOT_FOUND, when = NO_WALLET)
  @GetMapping("/balance")
  WalletBalanceResponse balance(@AuthenticationPrincipal Jwt jwt) {
    return WalletBalanceResponse.from(queries.balance(subject(jwt)));
  }

  @Operation(operationId = "getWalletStatus", summary = "Get the caller's wallet status")
  @ApiError(code = ErrorCode.WALLET_NOT_FOUND, when = NO_WALLET)
  @GetMapping("/status")
  WalletStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
    return WalletStatusResponse.from(queries.status(subject(jwt)));
  }

  @Operation(operationId = "getWalletLimits", summary = "Get the limits of the caller's tier")
  @ApiError(code = ErrorCode.WALLET_NOT_FOUND, when = NO_WALLET)
  @GetMapping("/limits")
  WalletLimitsResponse limits(@AuthenticationPrincipal Jwt jwt) {
    return WalletLimitsResponse.from(queries.limits(subject(jwt)));
  }

  /** Customer self-service freeze; audited. Freezing a frozen wallet changes nothing. */
  @Operation(
      operationId = "freezeWallet",
      summary = "Freeze the caller's wallet",
      description = "Freezing a frozen wallet changes nothing. Only staff unfreeze.")
  @ApiError(code = ErrorCode.WALLET_NOT_FOUND, when = NO_WALLET)
  @PostMapping("/freeze")
  WalletStatusResponse freeze(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return WalletStatusResponse.from(
        statuses.freezeOwn(subject(jwt), RequestText.clientIp(request)));
  }

  /** Simulated deposit (MVP-ADR-13). */
  @Operation(operationId = "createDeposit", summary = "Deposit simulated money")
  @ApiResponse(responseCode = "201", description = MOVED)
  @ApiError(code = ErrorCode.INVALID_AMOUNT, when = INVALID_AMOUNT)
  @ApiError(code = ErrorCode.CURRENCY_MISMATCH, when = NOT_THB)
  @ApiError(code = ErrorCode.KYC_REQUIRED, when = NO_WALLET)
  @ApiError(code = ErrorCode.IDEMPOTENCY_KEY_REUSED, when = KEY_REUSED)
  @ApiError(code = ErrorCode.WALLET_FROZEN, when = "the wallet is frozen")
  @ApiError(code = ErrorCode.SINGLE_LIMIT_EXCEEDED, when = OVER_SINGLE)
  @ApiError(code = ErrorCode.DAILY_LIMIT_EXCEEDED, when = OVER_DAILY)
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
  @Operation(operationId = "createWithdrawal", summary = "Withdraw simulated money")
  @ApiResponse(responseCode = "201", description = MOVED)
  @ApiError(code = ErrorCode.INVALID_AMOUNT, when = INVALID_AMOUNT)
  @ApiError(code = ErrorCode.CURRENCY_MISMATCH, when = NOT_THB)
  @ApiError(code = ErrorCode.KYC_REQUIRED, when = NO_WALLET)
  @ApiError(code = ErrorCode.IDEMPOTENCY_KEY_REUSED, when = KEY_REUSED)
  @ApiError(code = ErrorCode.WALLET_FROZEN, when = "the wallet is frozen")
  @ApiError(code = ErrorCode.SINGLE_LIMIT_EXCEEDED, when = OVER_SINGLE)
  @ApiError(code = ErrorCode.DAILY_LIMIT_EXCEEDED, when = OVER_DAILY)
  @ApiError(
      code = ErrorCode.INSUFFICIENT_BALANCE,
      when = "the available balance is lower than the amount")
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
