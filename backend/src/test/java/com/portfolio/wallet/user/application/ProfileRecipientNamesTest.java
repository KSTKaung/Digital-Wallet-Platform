package com.portfolio.wallet.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The masked name the lookup receives (documentations/06 MVP-ADR-20; doc 08 §4B.2 Q3). */
class ProfileRecipientNamesTest {

  private final CustomerRepository customers = mock(CustomerRepository.class);
  private final ProfileRecipientNames names = new ProfileRecipientNames(customers);

  private static CustomerRow row(UUID id, String first, String last) {
    return new CustomerRow(id, first, last, null, null, Instant.EPOCH, Instant.EPOCH);
  }

  @Test
  void onlyTheMaskedFormLeavesTheModule() {
    UUID id = UUID.randomUUID();
    when(customers.find(id)).thenReturn(Optional.of(row(id, "Somchai", "Jaidee")));

    assertThat(names.maskedLegalName(id)).contains("Somchai J.");
  }

  @Test
  void noProfileOrNoLegalNameYetIsEmpty() {
    UUID missing = UUID.randomUUID();
    UUID unnamed = UUID.randomUUID();
    UUID half = UUID.randomUUID();
    when(customers.find(missing)).thenReturn(Optional.empty());
    when(customers.find(unnamed)).thenReturn(Optional.of(row(unnamed, null, null)));
    when(customers.find(half)).thenReturn(Optional.of(row(half, "Somchai", null)));

    assertThat(names.maskedLegalName(missing)).isEmpty();
    assertThat(names.maskedLegalName(unnamed)).isEmpty();
    assertThat(names.maskedLegalName(half)).isEmpty();
  }
}
