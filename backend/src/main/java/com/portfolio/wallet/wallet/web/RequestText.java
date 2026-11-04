package com.portfolio.wallet.wallet.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** The client address for audit rows, as in the other modules' own copies. */
final class RequestText {

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
