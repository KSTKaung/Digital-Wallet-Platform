package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.security.AccessDenial;
import com.portfolio.wallet.common.security.AccessDenialAuditor;
import com.portfolio.wallet.common.security.AccessDenials;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the documented error body (documentations/02 §1.7). Unexpected errors are
 * logged server-side and returned as {@code SYSTEM_ERROR} without internal details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final AuthenticationTrustResolver TRUST_RESOLVER =
      new AuthenticationTrustResolverImpl();

  private final ObjectProvider<AccessDenialAuditor> auditors;

  public GlobalExceptionHandler(ObjectProvider<AccessDenialAuditor> auditors) {
    this.auditors = auditors;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
    if (ex.errorCode() == ErrorCode.FORBIDDEN) {
      AccessDenials.report(auditors, request, AccessDenial.Kind.ACCESS_DENIED);
    }
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

  // The name comes from the controller's declaration, not from the client's request data.
  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
    return body(ErrorCode.VALIDATION_ERROR, ex.getHeaderName() + ": header is required");
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ApiErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException ex) {
    return body(ErrorCode.VALIDATION_ERROR, ex.getParameterName() + ": parameter is required");
  }

  @ExceptionHandler(MissingRequestCookieException.class)
  ResponseEntity<ApiErrorResponse> handleMissingCookie(MissingRequestCookieException ex) {
    return body(ErrorCode.VALIDATION_ERROR, ex.getCookieName() + ": cookie is required");
  }

  // Constraints on controller method parameters (@RequestParam, @PathVariable and so on).
  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ApiErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
    String message =
        ex.getParameterValidationResults().stream()
            .findFirst()
            .map(result -> result.getMethodParameter().getParameterName())
            .map(name -> name + ": invalid value")
            .orElse("Request validation failed");
    return body(ErrorCode.VALIDATION_ERROR, message);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return body(ErrorCode.VALIDATION_ERROR, ex.getName() + ": invalid value");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
    return body(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
  }

  // Raised by method security (@PreAuthorize), inside MVC and so past the security filter's
  // handler.
  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    if (!TRUST_RESOLVER.isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
      return unauthenticated();
    }
    AccessDenials.report(auditors, request, AccessDenial.Kind.ACCESS_DENIED);
    return body(ErrorCode.FORBIDDEN, "Access denied");
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
    return unauthenticated();
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

  // Logged only, not audited: anyone can send these without limit (JsonSecurityErrorHandler).
  private static ResponseEntity<ApiErrorResponse> unauthenticated() {
    log.info("Refused an unauthenticated request");
    return body(ErrorCode.UNAUTHORIZED, "Authentication required");
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
