package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the documented error body (documentations/02 §1.7). Unexpected errors are
 * logged server-side and returned as {@code SYSTEM_ERROR} without internal details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrorResponse> handleApi(ApiException ex) {
    return body(ex.errorCode(), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(GlobalExceptionHandler::describe)
            .orElse("Request validation failed");
    return body(ErrorCode.VALIDATION_ERROR, message);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
    return body(ErrorCode.VALIDATION_ERROR, "Malformed request body");
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException ex) {
    return body(ErrorCode.NOT_FOUND, "Resource not found");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
    return body(ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return body(ErrorCode.SYSTEM_ERROR, "An unexpected error occurred");
  }

  private static ResponseEntity<ApiErrorResponse> body(ErrorCode code, String message) {
    return ResponseEntity.status(code.status())
        .body(ApiErrorResponse.of(code, message, CorrelationId.current().orElse(null)));
  }

  // Field name and constraint message only; the rejected value may be sensitive and is not echoed.
  private static String describe(FieldError error) {
    return error.getField() + ": " + error.getDefaultMessage();
  }
}
