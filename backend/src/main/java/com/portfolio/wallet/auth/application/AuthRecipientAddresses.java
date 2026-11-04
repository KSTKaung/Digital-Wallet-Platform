package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.notification.application.RecipientAddresses;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The notification module's {@link RecipientAddresses} over {@link UserContacts} (documentations/06
 * MVP-ADR-25): the outbox dispatcher looks the address up here when it sends, so no business module
 * holds an address. The module direction stays auth → notification.
 */
@Component
class AuthRecipientAddresses implements RecipientAddresses {

  private final UserContacts contacts;

  AuthRecipientAddresses(UserContacts contacts) {
    this.contacts = contacts;
  }

  @Override
  public Optional<String> emailOf(UUID userId, Duration timeout) {
    return contacts
        .find(userId, timeout)
        .map(ContactDetails::email)
        .filter(address -> !address.isBlank());
  }
}
