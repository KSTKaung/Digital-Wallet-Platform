package com.portfolio.wallet.common.security;

import java.util.UUID;

/**
 * Checks on each authenticated request that a token's session still stands, so logout, freeze and
 * password change take effect at once (documentations/06 MVP-ADR-23). Implemented by the auth
 * module; it lives here because {@code common} may not depend on a module.
 */
public interface SessionValidator {

  /**
   * @param sessionId the token's {@code sid} claim
   * @param subject the token's {@code sub} claim
   */
  boolean isActive(UUID sessionId, UUID subject);
}
