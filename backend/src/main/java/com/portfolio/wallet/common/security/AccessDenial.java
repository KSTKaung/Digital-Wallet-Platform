package com.portfolio.wallet.common.security;

import java.net.InetAddress;
import java.util.UUID;

/**
 * One refused request.
 *
 * @param actorId the authenticated user, or null when none is known; the CSRF check runs before
 *     authentication, so its refusals never have one
 * @param staff whether the authenticated user holds no customer role
 */
public record AccessDenial(
    Kind kind, UUID actorId, boolean staff, String method, String path, InetAddress clientIp) {

  public enum Kind {
    ACCESS_DENIED,
    CSRF_REFUSED
  }
}
