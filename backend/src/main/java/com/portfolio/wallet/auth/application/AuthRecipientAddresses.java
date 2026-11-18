package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.auth.domain.UserStatus;
import com.portfolio.wallet.auth.persistence.RecipientRow;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.notification.application.RecipientAddress;
import com.portfolio.wallet.notification.application.RecipientAddresses;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The notification module's {@link RecipientAddresses} over {@code auth.users} (documentations/06
 * MVP-ADR-25): the outbox dispatcher looks the address up here when it sends, so no business module
 * holds an address. The same read is the send-time recipient check (owner, 2026-11-14, decision 8):
 * an address only for status {@code ACTIVE}, the only status that can sign in. The module direction
 * stays auth → notification.
 */
@Component
class AuthRecipientAddresses implements RecipientAddresses {

  private final UserRepository users;

  AuthRecipientAddresses(UserRepository users) {
    this.users = users;
  }

  @Override
  public RecipientAddress lookup(UUID userId, Duration timeout) {
    Optional<RecipientRow> row = users.findRecipient(userId, timeout);
    if (row.isEmpty()) {
      return RecipientAddress.notFound();
    }
    if (!UserStatus.ACTIVE.name().equals(row.get().status())) {
      return RecipientAddress.notActive();
    }
    String email = row.get().email();
    return email == null || email.isBlank()
        ? RecipientAddress.notFound()
        : RecipientAddress.active(email);
  }
}
