package com.portfolio.wallet.wallet.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Request text rules and the client address for audit rows, as in the other modules' own copies.
 */
final class RequestText {

  /** No control, surrogate or format characters (J6). */
  static final String NO_CONTROL_CHARACTERS = "[^\\p{Cc}\\p{Cs}\\p{Cf}]*";

  static final String NO_CONTROL_CHARACTERS_MESSAGE =
      "must not contain control or format characters";

  /** Text a person reads: at least one letter or digit, not punctuation or symbols alone. */
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
