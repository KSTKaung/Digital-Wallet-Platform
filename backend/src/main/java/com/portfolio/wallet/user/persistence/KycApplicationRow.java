package com.portfolio.wallet.user.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One {@code usr.kyc_applications} row. {@code dateOfBirth} and {@code countryCode} are the
 * profile's values copied at submit (V8, K4), null for a draft. {@link #toString()} leaves out the
 * document reference, the names, the date of birth and the rejection reason (doc 03 §5.1).
 */
public record KycApplicationRow(
    UUID id,
    UUID customerId,
    String verificationLevel,
    String documentType,
    String documentReference,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String countryCode,
    String status,
    Instant submittedAt,
    Instant decidedAt,
    UUID reviewerId,
    String rejectionReason,
    Instant createdAt,
    Instant updatedAt) {

  @Override
  public String toString() {
    return "KycApplicationRow[id=" + id + ", status=" + status + "]";
  }
}
