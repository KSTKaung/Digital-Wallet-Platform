package com.portfolio.wallet.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.user.application.CustomerLegalNames.LegalNameView;
import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Full legal names for the staff transfer review (doc 06 MVP-ADR-24 amendment Q12). */
class CustomerLegalNamesTest {

  private final CustomerRepository customers = mock(CustomerRepository.class);
  private final CustomerLegalNames names = new CustomerLegalNames(customers);

  @Test
  void namesAreReadOncePerCustomerAndAMissingProfileHasNone() {
    UUID named = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    Instant now = Instant.now();
    when(customers.find(named))
        .thenReturn(Optional.of(new CustomerRow(named, "Somchai", "Jaidee", null, "TH", now, now)));
    when(customers.find(missing)).thenReturn(Optional.empty());

    var result = names.of(List.of(named, missing, named));

    assertThat(result.get(named)).isEqualTo(new LegalNameView("Somchai", "Jaidee"));
    assertThat(result.get(missing)).isEqualTo(new LegalNameView(null, null));
    assertThat(result.get(named).toString()).doesNotContain("Somchai");
    verify(customers, times(1)).find(named);
  }
}
