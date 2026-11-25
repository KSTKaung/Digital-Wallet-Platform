package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.web.ApiError;
import com.portfolio.wallet.common.web.OpenApiDocumentation;
import com.portfolio.wallet.wallet.application.WalletLookupService;
import com.portfolio.wallet.wallet.application.WalletLookupView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/wallets/lookup?walletNumber=} (documentations/02 §2.3; doc 06 MVP-ADR-20). The
 * wallet number travels in the query string, never in a path segment, because refused requests log
 * their path (doc 08 §4B PR 4b notes). {@code SecurityConfig} admits only {@code CUSTOMER} on
 * {@code /api/v1/wallets/**}; the annotation is the second layer. Not cached.
 */
@Tag(name = "Wallet", description = "The caller's own wallet, deposits and withdrawals")
@SecurityRequirement(name = OpenApiDocumentation.BEARER)
@RestController
@RequestMapping("/api/v1/wallets")
@PreAuthorize("hasRole('CUSTOMER')")
public class WalletLookupController {

  private final WalletLookupService lookups;

  public WalletLookupController(WalletLookupService lookups) {
    this.lookups = lookups;
  }

  @Operation(
      operationId = "lookupWallet",
      summary = "Find a recipient by exact wallet number",
      description = "Counted and audited; refusals for format, caller status or the limit are not.")
  @ApiError(code = ErrorCode.KYC_REQUIRED, when = "the caller has no wallet yet")
  @ApiError(
      code = ErrorCode.NOT_FOUND,
      when =
          "no ACTIVE wallet with this number, a wrong check digit, or the caller's own wallet;"
              + " identical in every case")
  @ApiError(code = ErrorCode.WALLET_FROZEN, when = "the caller's wallet is frozen")
  @ApiError(code = ErrorCode.RATE_LIMITED, when = "over the per-user or per-IP lookup window")
  @GetMapping("/lookup")
  WalletLookupResponse lookup(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam("walletNumber") String walletNumber,
      HttpServletRequest request) {
    WalletLookupView view =
        lookups.lookup(UUID.fromString(jwt.getSubject()), walletNumber, request.getRemoteAddr());
    return new WalletLookupResponse(view.maskedName(), view.maskedWalletNumber());
  }

  /** {@code {maskedName, maskedWalletNumber}} (doc 02 §2.3). */
  record WalletLookupResponse(String maskedName, String maskedWalletNumber) {

    @Override
    public String toString() {
      return "WalletLookupResponse[]";
    }
  }
}
