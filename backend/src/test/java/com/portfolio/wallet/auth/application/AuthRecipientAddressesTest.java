package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.auth.persistence.RecipientRow;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.notification.application.RecipientAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The outbox's address lookup over {@code auth.users} (documentations/06 MVP-ADR-25), and the
 * send-time recipient check (owner, 2026-11-14, decision 8): an address only for an {@code ACTIVE}
 * account.
 */
class AuthRecipientAddressesTest {

  private final UserRepository users = mock(UserRepository.class);
  private final AuthRecipientAddresses addresses = new AuthRecipientAddresses(users);
  private final UUID user = UUID.randomUUID();
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void anActiveAccountsEmailIsTheAddressLookedUpWithTheTimeout() {
    when(users.findRecipient(user, TIMEOUT))
        .thenReturn(Optional.of(new RecipientRow("c@example.test", "ACTIVE")));

    RecipientAddress found = addresses.lookup(user, TIMEOUT);

    assertThat(found.outcome()).isEqualTo(RecipientAddress.Outcome.ACTIVE);
    assertThat(found.email()).contains("c@example.test");
    assertThat(found.toString()).doesNotContain("c@example.test");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING_VERIFICATION", "FROZEN", "SUSPENDED"})
  void anAccountThatIsNotActiveHasNoAddress(String status) {
    when(users.findRecipient(user, TIMEOUT))
        .thenReturn(Optional.of(new RecipientRow("c@example.test", status)));

    RecipientAddress found = addresses.lookup(user, TIMEOUT);

    assertThat(found).isEqualTo(RecipientAddress.notActive());
    assertThat(found.email()).isEmpty();
  }

  @Test
  void anUnknownAccountOrABlankEmailIsNotFound() {
    when(users.findRecipient(user, TIMEOUT)).thenReturn(Optional.empty());
    assertThat(addresses.lookup(user, TIMEOUT)).isEqualTo(RecipientAddress.notFound());

    when(users.findRecipient(user, TIMEOUT))
        .thenReturn(Optional.of(new RecipientRow(" ", "ACTIVE")));
    assertThat(addresses.lookup(user, TIMEOUT)).isEqualTo(RecipientAddress.notFound());

    when(users.findRecipient(user, TIMEOUT))
        .thenReturn(Optional.of(new RecipientRow(null, "ACTIVE")));
    assertThat(addresses.lookup(user, TIMEOUT)).isEqualTo(RecipientAddress.notFound());
  }
}
