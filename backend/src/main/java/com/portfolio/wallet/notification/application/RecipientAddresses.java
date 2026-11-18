package com.portfolio.wallet.notification.application;

import java.time.Duration;
import java.util.UUID;

/**
 * Where a business email goes (documentations/06 MVP-ADR-25): the address is looked up when an
 * outbox row is sent, never stored with it, so no business module holds or looks up an address, and
 * no lookup runs while money locks are held. A port, because the notification module must not
 * depend on {@code auth}, which already depends on it; {@code auth.application} implements it.
 *
 * <p>The lookup is also the send-time check of the recipient (owner, 2026-11-14, decision 8): an
 * address is returned only for an account whose status is {@code ACTIVE}, so an email is not sent
 * once the lookup sees the account is not {@code ACTIVE} (frozen, suspended or otherwise
 * deactivated after its row was written). The status is read without a lock just before the send,
 * so an account deactivated between that read and the SMTP send can still get that one email
 * (accepted; documentations/06 MVP-ADR-24 "Emails (Q9)", amendment of 2026-11-14).
 *
 * <p>The address is personal data: the dispatcher passes it to the mail sender and never logs it.
 */
public interface RecipientAddresses {

  /**
   * @param timeout the longest the lookup may run in the database (S1); the dispatcher runs it with
   *     no transaction open, so it is a per-statement query timeout, in whole seconds
   * @return the address of an {@code ACTIVE} account; {@link RecipientAddress#notActive()} for an
   *     account in any other status; {@link RecipientAddress#notFound()} if there is no such
   *     account, or an {@code ACTIVE} one has no address
   * @throws org.springframework.dao.DataAccessException if the lookup fails or times out, which
   *     fails the attempt
   */
  RecipientAddress lookup(UUID userId, Duration timeout);
}
