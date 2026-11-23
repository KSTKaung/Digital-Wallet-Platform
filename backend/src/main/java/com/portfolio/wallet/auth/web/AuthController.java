package com.portfolio.wallet.auth.web;

import com.portfolio.wallet.auth.application.IssuedTokens;
import com.portfolio.wallet.auth.application.LoginService;
import com.portfolio.wallet.auth.application.PasswordService;
import com.portfolio.wallet.auth.application.RegistrationService;
import com.portfolio.wallet.auth.application.SessionService;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.security.JwtProperties;
import com.portfolio.wallet.common.web.ApiError;
import com.portfolio.wallet.common.web.OpenApiDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (documentations/02 §2.1; doc 06 MVP-ADR-14, -23). Public except {@code
 * change-password} and {@code logout}. The OpenAPI annotations describe the contract only.
 */
@Tag(name = "Auth", description = "Registration, login, sessions and passwords")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private static final String REFRESH_COOKIE = "refresh_token";
  private static final String COOKIE_PATH = "/api/v1/auth";
  private static final String STATUS_PENDING = "PENDING_VERIFICATION";

  private final RegistrationService registration;
  private final LoginService login;
  private final SessionService sessions;
  private final PasswordService passwords;

  public AuthController(
      RegistrationService registration,
      LoginService login,
      SessionService sessions,
      PasswordService passwords) {
    this.registration = registration;
    this.login = login;
    this.sessions = sessions;
    this.passwords = passwords;
  }

  @Operation(operationId = "registerAccount", summary = "Register a customer account")
  @ApiResponse(
      responseCode = "201",
      description =
          "Identical for a new and an existing account; a verification code is emailed to a new or"
              + " still unverified one",
      content = @Content(schema = @Schema(implementation = RegisterResponse.class)))
  @PostMapping("/register")
  ResponseEntity<RegisterResponse> register(
      @Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
    String email =
        registration.register(body.email(), body.phoneNumber(), body.password(), clientIp(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new RegisterResponse(email, STATUS_PENDING));
  }

  @Operation(operationId = "verifyOtp", summary = "Verify an emailed code")
  @ApiResponse(
      responseCode = "200",
      description =
          "PASSWORD_RESET: the body holds the reset token. REGISTRATION: an empty body; the"
              + " account is active.")
  @ApiError(
      code = ErrorCode.INVALID_OTP,
      when =
          "a wrong, expired, used-up or cancelled code, an unknown identifier, the purpose LOGIN,"
              + " or a missing or wrong password for REGISTRATION; identical for every failure")
  @PostMapping("/verify-otp")
  ResponseEntity<ResetTokenResponse> verifyOtp(
      @Valid @RequestBody VerifyOtpRequest body, HttpServletRequest request) {
    if (isPasswordReset(body.purpose())) {
      return ResponseEntity.ok(
          new ResetTokenResponse(
              passwords.verifyResetCode(body.identifier(), body.otp(), clientIp(request))));
    }
    registration.verify(
        body.identifier(), body.purpose(), body.otp(), body.password(), clientIp(request));
    return ResponseEntity.ok().build();
  }

  @Operation(operationId = "resendOtp", summary = "Send a new code")
  @ApiResponse(
      responseCode = "202",
      description =
          "Always, also for an unknown identifier or when a limit sends nothing; PASSWORD_RESET"
              + " behaves like forgot-password")
  @PostMapping("/resend-otp")
  ResponseEntity<Void> resendOtp(
      @Valid @RequestBody ResendOtpRequest body, HttpServletRequest request) {
    if (isPasswordReset(body.purpose())) {
      passwords.requestReset(body.identifier(), clientIp(request));
    } else {
      registration.resend(body.identifier(), body.purpose(), clientIp(request));
    }
    return ResponseEntity.accepted().build();
  }

  @Operation(operationId = "requestPasswordReset", summary = "Email a password-reset code")
  @ApiResponse(
      responseCode = "202",
      description = "Always; a code is emailed only to an account that can reset its password")
  @PostMapping("/forgot-password")
  ResponseEntity<Void> forgotPassword(
      @Valid @RequestBody ForgotPasswordRequest body, HttpServletRequest request) {
    passwords.requestReset(body.email(), clientIp(request));
    return ResponseEntity.accepted().build();
  }

  @Operation(operationId = "resetPassword", summary = "Set a new password with a reset token")
  @ApiResponse(responseCode = "200", description = "The password is reset; every session ends")
  @ApiError(
      code = ErrorCode.INVALID_RESET_TOKEN,
      when =
          "an unknown, expired or already used reset token, or one whose account can no longer"
              + " reset; the message is \"Invalid or expired reset token\"")
  @ApiError(
      code = ErrorCode.VALIDATION_ERROR,
      when = "newPassword outside the length policy, or a recent password repeated")
  @PostMapping("/reset-password")
  ResponseEntity<Void> resetPassword(
      @Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
    passwords.resetPassword(body.resetToken(), body.newPassword(), clientIp(request));
    return ResponseEntity.ok().build();
  }

  // Every session is revoked, the caller's too, so its refresh cookie goes as well.
  @Operation(operationId = "changePassword", summary = "Change the caller's password")
  @SecurityRequirement(name = OpenApiDocumentation.BEARER)
  @ApiResponse(
      responseCode = "200",
      description = "Changed; every session ends, this one too, and the refresh cookie is cleared")
  @ApiError(
      code = ErrorCode.VALIDATION_ERROR,
      when = "newPassword outside the length policy, or a recent password repeated")
  @ApiError(code = ErrorCode.UNAUTHORIZED, when = "also a wrong current password")
  @PostMapping("/change-password")
  ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ChangePasswordRequest body,
      HttpServletRequest request) {
    passwords.changePassword(
        UUID.fromString(jwt.getSubject()),
        body.currentPassword(),
        body.newPassword(),
        clientIp(request));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .build();
  }

  @Operation(operationId = "loginUser", summary = "Log in with email and password")
  @ApiResponse(
      responseCode = "200",
      description = "The access token; the refresh token is set in an HttpOnly cookie")
  @ApiError(
      code = ErrorCode.UNAUTHORIZED,
      when = "a wrong password, an unknown account or a locked account; identical for all three")
  @PostMapping("/login")
  ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    return withTokens(login.login(body.email(), body.password(), clientIp(request)));
  }

  // The cookie is optional here so that a missing one is the generic 401, not a validation error.
  @Operation(
      operationId = "refreshAccessToken",
      summary = "Get a new access token with the refresh cookie",
      description = "Requires the X-Requested-With header and an Origin on the allow-list.")
  @ApiResponse(
      responseCode = "200",
      description = "A new access token; the refresh cookie is rotated")
  @ApiError(
      code = ErrorCode.UNAUTHORIZED,
      when = "a missing, invalid, expired, revoked or reused refresh token, or an ended session")
  @ApiError(
      code = ErrorCode.FORBIDDEN,
      when = "no X-Requested-With header, or an Origin that is missing or not allowed")
  @PostMapping("/refresh")
  ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
      HttpServletRequest request) {
    return withTokens(sessions.refresh(refreshToken, clientIp(request)));
  }

  @Operation(
      operationId = "logoutUser",
      summary = "End the caller's session",
      description = "Requires the X-Requested-With header and an Origin on the allow-list.")
  @SecurityRequirement(name = OpenApiDocumentation.BEARER)
  @ApiResponse(responseCode = "204", description = "Signed out; the refresh cookie is cleared")
  @ApiError(
      code = ErrorCode.FORBIDDEN,
      when = "no X-Requested-With header, or an Origin that is missing or not allowed")
  @PostMapping("/logout")
  ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    sessions.logout(
        UUID.fromString(jwt.getClaimAsString(JwtProperties.SESSION_CLAIM)),
        UUID.fromString(jwt.getSubject()),
        clientIp(request));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .build();
  }

  private static boolean isPasswordReset(String purpose) {
    return OtpPurpose.PASSWORD_RESET.name().equals(purpose);
  }

  private static ResponseEntity<TokenResponse> withTokens(IssuedTokens tokens) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            cookie(tokens.refreshToken(), tokens.refreshMaxAge()).toString())
        .body(new TokenResponse(tokens.accessToken(), tokens.expiresInSeconds()));
  }

  private static ResponseCookie cookie(String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(maxAge)
        .build();
  }

  // The container supplies an IP literal, so this performs no DNS lookup. It is the real client's
  // address, because Tomcat takes X-Forwarded-For from trusted proxies only (SecurityConfig).
  private static InetAddress clientIp(HttpServletRequest request) {
    try {
      return InetAddress.getByName(request.getRemoteAddr());
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
