package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.error.ErrorCode;
import java.time.Instant;

/**
 * Error body returned by every endpoint (documentations/02 §1.7):
 *
 * <pre>{ "success": false, "errorCode": "...", "message": "...", "correlationId": "...",
 *   "timestamp": "..." }</pre>
 */
public record ApiErrorResponse(
    boolean success, String errorCode, String message, String correlationId, Instant timestamp) {

  public static ApiErrorResponse of(ErrorCode code, String message, String correlationId) {
    return new ApiErrorResponse(false, code.name(), message, correlationId, Instant.now());
  }
}
