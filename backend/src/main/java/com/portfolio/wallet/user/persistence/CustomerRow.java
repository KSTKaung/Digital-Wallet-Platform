package com.portfolio.wallet.user.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One {@code usr.customers} row. The legal name is null until a KYC approval copies it. {@link
 * #toString()} leaves out the name and the date of birth (Confidential, doc 03 §5.1).
 */
public record CustomerRow(
    UUID userId,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String countryCode,
    Instant createdAt,
    Instant updatedAt) {

  @Override
  public String toString() {
    return "CustomerRow[userId=" + userId + "]";
  }
}
