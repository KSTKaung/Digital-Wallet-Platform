package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.web.ApiError;
import com.portfolio.wallet.common.web.OpenApiDocumentation;
import com.portfolio.wallet.common.web.PageResponse;
import com.portfolio.wallet.user.application.KycReviewPage;
import com.portfolio.wallet.user.application.KycReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Admin", description = "Staff: KYC decisions, transfer reviews, wallet freeze")
@SecurityRequirement(name = OpenApiDocumentation.BEARER)
@RestController
@RequestMapping("/api/v1/admin/kyc/applications")
@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
public class KycReviewController {

  /** doc 02 §1.5. */
  static final int MAX_PAGE_SIZE = 100;

  private static final String OWN_APPLICATION = "the reviewer's own application";

  private final KycReviewService reviews;

  public KycReviewController(KycReviewService reviews) {
    this.reviews = reviews;
  }

  /**
   * Submitted applications in {@code status} (default {@code PENDING_REVIEW}), oldest submission
   * first.
   */
  @Operation(
      operationId = "listKycApplicationsForReview",
      summary = "List submitted KYC applications",
      description = "Oldest submission first; drafts are never listed.")
  @ApiError(
      code = ErrorCode.VALIDATION_ERROR,
      when = "status other than PENDING_REVIEW, APPROVED or REJECTED")
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

  @Operation(
      operationId = "getKycApplicationForReview",
      summary = "Get a submitted KYC application",
      description = "A DRAFT is the same 404 as a missing id.")
  @GetMapping("/{id}")
  KycReviewDetailResponse get(@PathVariable UUID id) {
    return KycReviewDetailResponse.from(reviews.get(id));
  }

  @Operation(
      operationId = "approveKycApplication",
      summary = "Approve a KYC application; opens the wallet")
  @ApiError(code = ErrorCode.FORBIDDEN, when = OWN_APPLICATION)
  @ApiError(
      code = ErrorCode.INVALID_KYC_STATE,
      when = "no longer PENDING_REVIEW, or the customer already has a legal name or a wallet")
  @PostMapping("/{id}/approve")
  KycReviewDetailResponse approve(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
    return KycReviewDetailResponse.from(
        reviews.approve(reviewer(jwt), id, RequestText.clientIp(request)));
  }

  @Operation(
      operationId = "rejectKycApplication",
      summary = "Reject a KYC application",
      description = "The customer sees the reason: never put identity details in it.")
  @ApiError(
      code = ErrorCode.VALIDATION_ERROR,
      when = "a reason that contains the application's document reference")
  @ApiError(code = ErrorCode.FORBIDDEN, when = OWN_APPLICATION)
  @ApiError(code = ErrorCode.INVALID_KYC_STATE, when = "no longer PENDING_REVIEW")
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
