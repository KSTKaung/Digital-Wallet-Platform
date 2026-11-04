package com.portfolio.wallet.notification.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a business email goes (documentations/06 MVP-ADR-25): the address is looked up when an
 * outbox row is sent, never stored with it, so no business module holds or looks up an address, and
 * no lookup runs while money locks are held. A port, because the notification module must not
 * depend on {@code auth}, which already depends on it; {@code auth.application} implements it.
 *
 * <p>The address is personal data: the dispatcher passes it to the mail sender and never logs it.
 */
public interface RecipientAddresses {

  /**
   * @param timeout the longest the lookup may run in the database (S1); the dispatcher runs it with
   *     no transaction open, so it is a per-statement query timeout, in whole seconds
   * @return the account's email address, or empty if there is no such account or it has none
   * @throws org.springframework.dao.DataAccessException if the lookup fails or times out, which
   *     fails the attempt
   */
  Optional<String> emailOf(UUID userId, Duration timeout);
}
