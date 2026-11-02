package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import java.time.Duration;

/** The text of the emails this module sends. */
final class AuthEmails {

  private AuthEmails() {}

  static EmailMessage otp(String to, String code, Duration ttl) {
    return new EmailMessage(
        EmailKind.OTP,
        to,
        "Your verification code",
        "Your verification code is "
            + code
            + ". It is valid for "
            + ttl.toMinutes()
            + " minutes. Never share it with anyone.");
  }

  static EmailMessage existingAccount(String to) {
    return new EmailMessage(
        EmailKind.EXISTING_ACCOUNT_NOTICE,
        to,
        "Registration attempt on your account",
        "Someone tried to register using your email address or phone number. You already have an"
            + " account, so nothing was changed. If this was not you, you can ignore this message.");
  }

  static EmailMessage accountLocked(String to, Duration lockDuration) {
    return new EmailMessage(
        EmailKind.ACCOUNT_LOCKED,
        to,
        "Your account has been locked",
        "Too many failed login attempts were made on your account, so login is locked for "
            + lockDuration.toMinutes()
            + " minutes. If this was not you, consider changing your password.");
  }
}
