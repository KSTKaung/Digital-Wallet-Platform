package com.portfolio.wallet.user.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * What the request fields may contain, as in the auth module's own copy (another module's web
 * package is off limits). A NUL cannot be bound in PostgreSQL text and a lone surrogate cannot be
 * encoded, so both would otherwise end as a 500. Format characters (zero-width characters and
 * bidirectional marks) are invisible and can make a text read differently from what it contains, so
 * they are refused too (J6), as the domain does. Messages name the field, never the value.
 */
final class RequestText {

  static final String NO_CONTROL_CHARACTERS = "[^\\p{Cc}\\p{Cs}\\p{Cf}]*";

  static final String NO_CONTROL_CHARACTERS_MESSAGE =
      "must not contain control or format characters";

  // Legal names use @LegalNamePart, which runs the domain rule in one linear pass (T3): a pattern
  // with a repeated alternation group recurses per character and can overflow the stack on long
  // input. The plain character-class patterns here and in the other modules do not.

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
