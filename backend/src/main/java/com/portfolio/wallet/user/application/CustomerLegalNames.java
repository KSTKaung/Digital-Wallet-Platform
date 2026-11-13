package com.portfolio.wallet.user.application;

import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers' full legal names for the staff transfer review detail (documentations/06 MVP-ADR-24
 * amendment "Review queue (Q12)": the sender's and the recipient's full legal names). Confidential
 * data for compliance officers only; only the {@code admin} module depends on this module (ArchUnit
 * {@code onlyAdminDependsOnUser}). Customers only ever see the masked form ({@link
 * ProfileRecipientNames}). Read-only, outside any money transaction; nothing is logged.
 */
@Service
public class CustomerLegalNames {

  /** A legal name as approved; both parts null if the customer has none yet. */
  public record LegalNameView(String firstName, String lastName) {

    static final LegalNameView NONE = new LegalNameView(null, null);

    @Override
    public String toString() {
      return "LegalNameView[]";
    }
  }

  private final CustomerRepository customers;

  public CustomerLegalNames(CustomerRepository customers) {
    this.customers = Objects.requireNonNull(customers, "customers");
  }

  /** The legal name of each customer id; a customer without a profile maps to {@code NONE}. */
  @Transactional(propagation = Propagation.NEVER, readOnly = true)
  public Map<UUID, LegalNameView> of(Iterable<UUID> customerIds) {
    Objects.requireNonNull(customerIds, "customerIds");
    Map<UUID, LegalNameView> names = new LinkedHashMap<>();
    for (UUID id : customerIds) {
      names.computeIfAbsent(
          id, key -> customers.find(key).map(CustomerLegalNames::name).orElse(LegalNameView.NONE));
    }
    return names;
  }

  private static LegalNameView name(CustomerRow row) {
    return new LegalNameView(row.firstName(), row.lastName());
  }
}
