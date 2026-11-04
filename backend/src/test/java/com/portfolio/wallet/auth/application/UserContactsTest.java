package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.auth.persistence.ContactRow;
import com.portfolio.wallet.auth.persistence.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The read-only contact port for the profile (documentations/08 §4B.3). */
class UserContactsTest {

  private final UserRepository users = mock(UserRepository.class);
  private final UserContacts contacts = new UserContacts(users);

  @Test
  void returnsTheStoredEmailAndPhoneWithoutPrintingThem() {
    UUID id = UUID.randomUUID();
    when(users.findContacts(id))
        .thenReturn(Optional.of(new ContactRow("alice@example.com", "+66812345678")));

    ContactDetails details = contacts.find(id).orElseThrow();

    assertThat(details.email()).isEqualTo("alice@example.com");
    assertThat(details.phoneNumber()).isEqualTo("+66812345678");
    assertThat(details.toString()).doesNotContain("alice", "66812345678");
    assertThat(new ContactRow("alice@example.com", "+6681").toString()).doesNotContain("alice");
  }

  @Test
  void anUnknownUserIsEmpty() {
    UUID id = UUID.randomUUID();
    when(users.findContacts(id)).thenReturn(Optional.empty());

    assertThat(contacts.find(id)).isEmpty();
  }

  /** S1: the timed lookup passes its timeout to the statement. */
  @Test
  void theTimedLookupPassesItsTimeout() {
    UUID id = UUID.randomUUID();
    java.time.Duration timeout = java.time.Duration.ofSeconds(5);
    when(users.findContacts(id, timeout))
        .thenReturn(Optional.of(new ContactRow("alice@example.com", null)));

    assertThat(contacts.find(id, timeout)).map(ContactDetails::email).contains("alice@example.com");
  }
}
