package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.admin.application.TransferReviewPage;
import com.portfolio.wallet.admin.application.TransferReviewService;
import com.portfolio.wallet.common.web.PageResponse;
import com.portfolio.wallet.transfer.application.TransferReviewQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfer reviews for compliance officers (documentations/02 §2.7; doc 06 MVP-ADR-12, MVP-ADR-24;
 * doc 08 §4C.2 Q12, Q15). {@code SecurityConfig} admits only {@code COMPLIANCE_OFFICER} to this
 * path before any body is read; the annotation is the second layer. The reviewer is the JWT
 * subject.
 *
 * <ul>
 *   <li>{@code GET}: the queue ({@code PENDING_REVIEW}, soonest deadline first, size 1–100) and the
 *       detail of any transfer that was held for review;
 *   <li>{@code POST …/approve} and {@code …/reject {reason}}: {@code 200} with the detail as
 *       committed; {@code 409 INVALID_TRANSFER_STATE} if no longer held or past the deadline (then
 *       expired); no {@code Idempotency-Key}, and only the general rate limit (Q15).
 * </ul>
 *
 * The reject reason is not validated here: the deadline is checked before the reason (doc 08 §5,
 * step 3 notes), so a late rejection expires the transfer whatever its reason: invalid, blank,
 * {@code null}, absent from the JSON object, or with no body at all (the body is optional, and a
 * missing one is a missing reason). The reason is checked by the service under the locks, only
 * before the deadline.
 *
 * <p>Protocol errors stay protocol errors and are answered before anything is locked: a body that
 * is not readable JSON is {@code 400 VALIDATION_ERROR} and a body in another media type is {@code
 * 415 UNSUPPORTED_MEDIA_TYPE}, by the global JSON handling, also after the deadline. Such a request
 * expires nothing; the transfer stays held until a valid action or the expiry job decides it.
 */
@RestController
@RequestMapping("/api/v1/admin/transfer-reviews")
@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
public class TransferReviewController {

  private final TransferReviewService reviews;

  public TransferReviewController(TransferReviewService reviews) {
    this.reviews = reviews;
  }

  @GetMapping
  PageResponse<TransferReviewSummaryResponse> queue(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(TransferReviewQueryService.MAX_PAGE_SIZE)
          int size) {
    TransferReviewPage result = reviews.queue(page, size);
    return PageResponse.of(
        result.items().stream().map(TransferReviewSummaryResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }

  @GetMapping("/{transferId}")
  TransferReviewDetailResponse get(@PathVariable UUID transferId) {
    return TransferReviewDetailResponse.from(reviews.get(transferId));
  }

  @PostMapping("/{transferId}/approve")
  TransferReviewDetailResponse approve(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID transferId, HttpServletRequest request) {
    return TransferReviewDetailResponse.from(
        reviews.approve(reviewer(jwt), transferId, RequestText.clientIp(request)));
  }

  @PostMapping("/{transferId}/reject")
  TransferReviewDetailResponse reject(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID transferId,
      @RequestBody(required = false) RejectTransferRequest body,
      HttpServletRequest request) {
    return TransferReviewDetailResponse.from(
        reviews.reject(
            reviewer(jwt),
            transferId,
            body == null ? null : body.reason(),
            RequestText.clientIp(request)));
  }

  private static UUID reviewer(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
