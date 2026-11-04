package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The outbox's address lookup over the auth module's contacts (documentations/06 MVP-ADR-25). */
class AuthRecipientAddressesTest {

  private final UserContacts contacts = mock(UserContacts.class);
  private final AuthRecipientAddresses addresses = new AuthRecipientAddresses(contacts);
  private final UUID user = UUID.randomUUID();
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void theAccountsEmailIsTheAddress() {
    when(contacts.find(user, TIMEOUT))
        .thenReturn(Optional.of(new ContactDetails("c@example.test", "+66812345678")));

    assertThat(addresses.emailOf(user, TIMEOUT)).contains("c@example.test");
  }

  @Test
  void anUnknownAccountOrABlankEmailHasNoAddress() {
    when(contacts.find(user, TIMEOUT)).thenReturn(Optional.empty());
    assertThat(addresses.emailOf(user, TIMEOUT)).isEmpty();

    when(contacts.find(user, TIMEOUT)).thenReturn(Optional.of(new ContactDetails(" ", null)));
    assertThat(addresses.emailOf(user, TIMEOUT)).isEmpty();

    when(contacts.find(user, TIMEOUT)).thenReturn(Optional.of(new ContactDetails(null, null)));
    assertThat(addresses.emailOf(user, TIMEOUT)).isEmpty();
  }
}
