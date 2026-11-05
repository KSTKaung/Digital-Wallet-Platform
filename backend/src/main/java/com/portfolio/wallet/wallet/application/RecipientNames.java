package com.portfolio.wallet.wallet.application;

import java.util.Optional;
import java.util.UUID;

/**
 * The masked legal name of a lookup's recipient (documentations/06 MVP-ADR-20; doc 08 §4B.2 Q3).
 * Declared here and implemented by {@code user.application}, so the wallet module never depends on
 * the user module (ArchUnit {@code moneyCoreDependsDownwardOnly}) and the full legal name never
 * enters it: only the masked form crosses the boundary.
 */
public interface RecipientNames {

  /**
   * @return the first name plus the initial of the last name (for example {@code Somchai J.}), or
   *     empty if the customer has no legal name yet
   */
  Optional<String> maskedLegalName(UUID customerId);
}
