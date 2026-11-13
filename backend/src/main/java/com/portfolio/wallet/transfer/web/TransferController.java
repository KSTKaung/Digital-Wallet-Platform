package com.portfolio.wallet.transfer.web;

import com.portfolio.wallet.common.security.ClientAddresses;
import com.portfolio.wallet.common.web.PageResponse;
import com.portfolio.wallet.transfer.application.TransferCancellationService;
import com.portfolio.wallet.transfer.application.TransferCommand;
import com.portfolio.wallet.transfer.application.TransferPage;
import com.portfolio.wallet.transfer.application.TransferQueryService;
import com.portfolio.wallet.transfer.application.TransferService;
import com.portfolio.wallet.transfer.application.TransferStatusView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer transfers (documentations/02 §2.4; doc 06 MVP-ADR-03, -06, -19, -20, -24; doc 08 §4C).
 * The customer is the JWT subject; ids in paths are checked against it in the services, so another
 * customer's transfer is the identical {@code 404}. {@code SecurityConfig} admits only {@code
 * CUSTOMER} on {@code /api/v1/transfers/**}; the annotation is the second layer. Responses are not
 * cached (Spring Security's {@code Cache-Control: no-store}).
 *
 * <ul>
 *   <li>{@code POST /transfers}: {@code Idempotency-Key} required; counted toward the 30-per-minute
 *       money limit per customer (doc 02 §1.8, Q15);
 *   <li>{@code POST /transfers/{id}/cancel}: no {@code Idempotency-Key}; only the general
 *       300-per-minute limit (Q15);
 *   <li>the three reads apply the one visibility rule of Q8.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/transfers")
@PreAuthorize("hasRole('CUSTOMER')")
public class TransferController {

  private final TransferService transfers;
  private final TransferCancellationService cancellations;
  private final TransferQueryService queries;

  public TransferController(
      TransferService transfers,
      TransferCancellationService cancellations,
      TransferQueryService queries) {
    this.transfers = transfers;
    this.cancellations = cancellations;
    this.queries = queries;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  TransferResponse create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
      @Valid @RequestBody CreateTransferRequest body,
      HttpServletRequest request) {
    UUID key = IdempotencyKeyHeader.parse(idempotencyKey);
    return TransferResponse.from(
        transfers.create(
            new TransferCommand(
                subject(jwt),
                key,
                body.receiverWalletNumber(),
                body.amount(),
                body.currency(),
                body.description(),
                request.getRemoteAddr())));
  }

  /** Newest first, size 1–100 (doc 02 §1.5). */
  @GetMapping
  PageResponse<TransferResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(TransferQueryService.MAX_PAGE_SIZE)
          int size) {
    TransferPage result = queries.list(subject(jwt), page, size);
    return PageResponse.of(
        result.items().stream().map(TransferResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }

  @GetMapping("/{id}")
  TransferResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return TransferResponse.from(queries.get(subject(jwt), id));
  }

  /** {@code {status, ledgerStatus}} (doc 02 §2.4). */
  @GetMapping("/{id}/status")
  TransferStatusResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    TransferStatusView view = queries.status(subject(jwt), id);
    return new TransferStatusResponse(view.status(), view.ledgerStatus());
  }

  /** Only the sender, only while {@code PENDING_REVIEW} and before the deadline. */
  @PostMapping("/{id}/cancel")
  TransferResponse cancel(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
    return TransferResponse.from(
        cancellations.cancel(subject(jwt), id, ClientAddresses.parse(request.getRemoteAddr())));
  }

  /** {@code {status, ledgerStatus}}. */
  record TransferStatusResponse(String status, String ledgerStatus) {}

  private static UUID subject(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
