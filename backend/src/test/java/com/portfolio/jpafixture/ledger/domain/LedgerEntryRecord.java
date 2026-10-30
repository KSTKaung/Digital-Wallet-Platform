package com.portfolio.jpafixture.ledger.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

/** Violation: a financial domain class that is a JPA entity (documentations/06 MVP-ADR-07). */
@Entity
public class LedgerEntryRecord {
  @Id UUID id;
}
