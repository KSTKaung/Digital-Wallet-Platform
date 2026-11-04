package com.portfolio.wallet.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * What the request fields may contain, as in the other modules' own copies (another module's web
 * package is off limits). A NUL cannot be bound in PostgreSQL text and a lone surrogate cannot be
 * encoded, so both would otherwise end as a 500. Format characters (zero-width characters and
 * bidirectional marks) are invisible and can make a reason read differently from what it contains,
 * so they are refused too (J6). Messages name the field, never the value.
 */
final class RequestText {

  static final String NO_CONTROL_CHARACTERS = "[^\\p{Cc}\\p{Cs}\\p{Cf}]*";

  static final String NO_CONTROL_CHARACTERS_MESSAGE =
      "must not contain control or format characters";

  /** A reason a person reads: at least one letter or digit, not punctuation or symbols alone. */
  static final String HAS_LETTER_OR_DIGIT = "(?s).*[\\p{L}\\p{N}].*";

  static final String HAS_LETTER_OR_DIGIT_MESSAGE = "must contain a letter or a digit";

  private RequestText() {}

  // The container supplies an IP literal, so this performs no DNS lookup. Tomcat takes
  // X-Forwarded-For from trusted proxies only (SecurityConfig).
  static InetAddress clientIp(HttpServletRequest request) {
    try {
      return InetAddress.getByName(request.getRemoteAddr());
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
