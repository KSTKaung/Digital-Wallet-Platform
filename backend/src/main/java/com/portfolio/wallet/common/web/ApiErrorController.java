package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.error.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default {@code /error} body for errors raised before MVC, such as firewall
 * rejections and exceptions thrown in filters (documentations/06 MVP-ADR-19). It answers with the
 * documented error body and a generic message per status: the original message, exception and path
 * are never read into the response.
 *
 * <p>A direct request to {@code /error}, which is public, carries no error status and gets the
 * ordinary 404.
 */
@RestController
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ApiErrorController implements ErrorController {

  @RequestMapping
  ResponseEntity<ApiErrorResponse> error(HttpServletRequest request) {
    int status =
        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
                && code >= 400
            ? code
            : ErrorCode.NOT_FOUND.status().value();
    ErrorCode code = codeFor(status);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiErrorResponse.of(code, messageFor(code), CorrelationId.current().orElse(null)));
  }

  // A status without a code of its own keeps that status, under the nearest generic code.
  private static ErrorCode codeFor(int status) {
    return switch (status) {
      case 401 -> ErrorCode.UNAUTHORIZED;
      case 403 -> ErrorCode.FORBIDDEN;
      case 404 -> ErrorCode.NOT_FOUND;
      case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
      case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
      case 429 -> ErrorCode.RATE_LIMITED;
      default -> status >= 500 ? ErrorCode.SYSTEM_ERROR : ErrorCode.VALIDATION_ERROR;
    };
  }

  private static String messageFor(ErrorCode code) {
    return switch (code) {
      case UNAUTHORIZED -> "Authentication required";
      case FORBIDDEN -> "Access denied";
      case NOT_FOUND -> "Resource not found";
      case METHOD_NOT_ALLOWED -> "Method not allowed";
      case UNSUPPORTED_MEDIA_TYPE -> "Unsupported media type";
      case RATE_LIMITED -> "Too many requests";
      case VALIDATION_ERROR -> "Invalid request";
      default -> "An unexpected error occurred";
    };
  }
}
