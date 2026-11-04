package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.common.web.PageResponse;
import com.portfolio.wallet.user.application.KycReviewPage;
import com.portfolio.wallet.user.application.KycReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
 * KYC decisions for compliance officers (documentations/02 §2.7; doc 06 MVP-ADR-10 amendment,
 * MVP-ADR-12). {@code SecurityConfig} admits only {@code COMPLIANCE_OFFICER} to this path before
 * any body is read; the annotation is the second layer. The reviewer is the JWT subject.
 */
@RestController
@RequestMapping("/api/v1/admin/kyc/applications")
@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
public class KycReviewController {

  /** doc 02 §1.5. */
  static final int MAX_PAGE_SIZE = 100;

  private final KycReviewService reviews;

  public KycReviewController(KycReviewService reviews) {
    this.reviews = reviews;
  }

  /** Submitted applications in {@code status} (default {@code PENDING_REVIEW}), newest first. */
  @GetMapping
  PageResponse<KycReviewSummaryResponse> list(
      @RequestParam(defaultValue = "PENDING_REVIEW") String status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    KycReviewPage result = reviews.list(status, page, size);
    return PageResponse.of(
        result.items().stream().map(KycReviewSummaryResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }

  @GetMapping("/{id}")
  KycReviewDetailResponse get(@PathVariable UUID id) {
    return KycReviewDetailResponse.from(reviews.get(id));
  }

  @PostMapping("/{id}/approve")
  KycReviewDetailResponse approve(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
    return KycReviewDetailResponse.from(
        reviews.approve(reviewer(jwt), id, RequestText.clientIp(request)));
  }

  @PostMapping("/{id}/reject")
  KycReviewDetailResponse reject(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody RejectKycRequest body,
      HttpServletRequest request) {
    return KycReviewDetailResponse.from(
        reviews.reject(reviewer(jwt), id, body.reason(), RequestText.clientIp(request)));
  }

  private static UUID reviewer(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
