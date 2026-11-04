package com.portfolio.wallet.user.domain;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Rules for the profile fields the customer edits (documentations/02 §2.2). No minimum age applies,
 * and a date of birth may not be in the future; today counts as valid (owner, 2026-11-03).
 */
public final class CustomerProfileRules {

  private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

  private CustomerProfileRules() {}

  /**
   * @param today the current business date (Asia/Bangkok in the MVP) from the injected clock
   */
  public static LocalDate requireDateOfBirth(LocalDate dateOfBirth, LocalDate today) {
    Objects.requireNonNull(today, "today");
    if (dateOfBirth == null) {
      throw new InvalidKycDetailsException("dateOfBirth", "must not be null");
    }
    if (dateOfBirth.isAfter(today)) {
      throw new InvalidKycDetailsException("dateOfBirth", "must not be in the future");
    }
    return dateOfBirth;
  }

  /** An ISO 3166-1 alpha-2 code in upper case, such as {@code TH}. */
  public static String requireCountryCode(String countryCode) {
    if (countryCode == null || !ISO_COUNTRIES.contains(countryCode)) {
      throw new InvalidKycDetailsException("countryCode", "must be an ISO 3166-1 alpha-2 code");
    }
    return countryCode;
  }
}
