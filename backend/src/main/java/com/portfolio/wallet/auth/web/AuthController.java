package com.portfolio.wallet.auth.web;

import com.portfolio.wallet.auth.application.IssuedTokens;
import com.portfolio.wallet.auth.application.LoginService;
import com.portfolio.wallet.auth.application.PasswordService;
import com.portfolio.wallet.auth.application.RegistrationService;
import com.portfolio.wallet.auth.application.SessionService;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import com.portfolio.wallet.common.security.JwtProperties;
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

/** Authentication endpoints (documentations/02 §2.1; doc 06 MVP-ADR-14, -23). */
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

  @PostMapping("/register")
  ResponseEntity<RegisterResponse> register(
      @Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
    String email =
        registration.register(body.email(), body.phoneNumber(), body.password(), clientIp(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new RegisterResponse(email, STATUS_PENDING));
  }

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

  @PostMapping("/forgot-password")
  ResponseEntity<Void> forgotPassword(
      @Valid @RequestBody ForgotPasswordRequest body, HttpServletRequest request) {
    passwords.requestReset(body.email(), clientIp(request));
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/reset-password")
  ResponseEntity<Void> resetPassword(
      @Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
    passwords.resetPassword(body.resetToken(), body.newPassword(), clientIp(request));
    return ResponseEntity.ok().build();
  }

  // Every session is revoked, the caller's too, so its refresh cookie goes as well.
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

  @PostMapping("/login")
  ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    return withTokens(login.login(body.email(), body.password(), clientIp(request)));
  }

  // The cookie is optional here so that a missing one is the generic 401, not a validation error.
  @PostMapping("/refresh")
  ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
      HttpServletRequest request) {
    return withTokens(sessions.refresh(refreshToken, clientIp(request)));
  }

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
