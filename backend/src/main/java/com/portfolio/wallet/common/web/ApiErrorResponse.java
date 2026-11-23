package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Error body returned by every endpoint (documentations/02 §1.7):
 *
 * <pre>{ "success": false, "errorCode": "...", "message": "...", "correlationId": "...",
 *   "timestamp": "..." }</pre>
 */
public record ApiErrorResponse(
    @Schema(description = "Always false") boolean success,
    @Schema(description = "The error code (documentations/02 §1.7, doc 06 MVP-ADR-19)")
        String errorCode,
    @Schema(description = "A generic message; never a secret, a token or a rejected value")
        String message,
    @Schema(nullable = true, description = "The request's correlation id (X-Correlation-Id)")
        String correlationId,
    Instant timestamp) {

  public static ApiErrorResponse of(ErrorCode code, String message, String correlationId) {
    return new ApiErrorResponse(false, code.name(), message, correlationId, Instant.now());
  }
}
