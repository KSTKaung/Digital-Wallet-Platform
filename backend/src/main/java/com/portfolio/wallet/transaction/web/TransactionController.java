package com.portfolio.wallet.transaction.web;

import com.portfolio.wallet.common.web.PageResponse;
import com.portfolio.wallet.transaction.application.TransactionHistoryService;
import com.portfolio.wallet.transaction.application.TransactionPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's history, read from the ledger (documentations/02 §1.5, §2.4 "MVP history"; doc 06
 * MVP-ADR-11 amendment). The customer is the JWT subject and the endpoint takes no id, so only the
 * caller's own entries can be listed. {@code SecurityConfig} admits only {@code CUSTOMER} on {@code
 * /api/v1/transactions/**}; the annotation is the second layer. There is no {@code GET
 * /transactions/{id}} in the MVP. Responses are not cached (Spring Security's {@code Cache-Control:
 * no-store}).
 */
@RestController
@RequestMapping("/api/v1/transactions")
@PreAuthorize("hasRole('CUSTOMER')")
public class TransactionController {

  private final TransactionHistoryService history;

  public TransactionController(TransactionHistoryService history) {
    this.history = history;
  }

  /** Newest first, size 1–100 (doc 02 §1.5). */
  @GetMapping
  PageResponse<TransactionResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(TransactionHistoryService.MAX_PAGE_SIZE)
          int size) {
    TransactionPage result = history.list(UUID.fromString(jwt.getSubject()), page, size);
    return PageResponse.of(
        result.items().stream().map(TransactionResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
