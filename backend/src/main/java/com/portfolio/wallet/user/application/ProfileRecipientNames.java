package com.portfolio.wallet.user.application;

import com.portfolio.wallet.user.domain.LegalName;
import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.wallet.application.RecipientNames;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link RecipientNames} over the profile (module direction user → wallet; documentations/08 §4B.2
 * Q3). The legal name is copied to the profile on KYC approval (MVP-ADR-10); only its masked form
 * leaves this module (MVP-ADR-20).
 */
@Component
class ProfileRecipientNames implements RecipientNames {

  private final CustomerRepository customers;

  ProfileRecipientNames(CustomerRepository customers) {
    this.customers = customers;
  }

  @Override
  public Optional<String> maskedLegalName(UUID customerId) {
    return customers
        .find(customerId)
        .filter(row -> row.firstName() != null && row.lastName() != null)
        .map(row -> new LegalName(row.firstName(), row.lastName()).masked());
  }
}
