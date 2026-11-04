package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.auth.persistence.UserRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Read-only access to an account's contact details for other modules (the customer profile, PR 4a).
 * It changes nothing, and the auth module depends on none of its callers.
 */
@Service
public class UserContacts {

  private final UserRepository users;

  public UserContacts(UserRepository users) {
    this.users = users;
  }

  public Optional<ContactDetails> find(UUID userId) {
    return users
        .findContacts(userId)
        .map(row -> new ContactDetails(row.email(), row.phoneNumber()));
  }

  /**
   * As {@link #find}, with a per-statement query timeout (S1), for callers that must not wait long
   * on the database, such as the email outbox. A timeout throws a {@code DataAccessException}.
   */
  public Optional<ContactDetails> find(UUID userId, Duration timeout) {
    return users
        .findContacts(userId, timeout)
        .map(row -> new ContactDetails(row.email(), row.phoneNumber()));
  }
}
