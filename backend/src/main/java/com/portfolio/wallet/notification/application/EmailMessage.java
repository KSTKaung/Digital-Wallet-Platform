package com.portfolio.wallet.notification.application;

import java.util.Objects;

/**
 * A plain-text email. The body can hold an OTP and the recipient is personal data, so {@link
 * #toString()} shows only the kind.
 */
public record EmailMessage(EmailKind kind, String to, String subject, String body) {

  public EmailMessage {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(body, "body");
    requireSingleLine("to", to);
    requireSingleLine("subject", subject);
  }

  @Override
  public String toString() {
    return "EmailMessage[kind=" + kind + "]";
  }

  // CR or LF in a header field would let its value add further headers.
  private static void requireSingleLine(String field, String value) {
    if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) {
      throw new IllegalArgumentException(field + " must be a non-blank single line");
    }
  }
}
