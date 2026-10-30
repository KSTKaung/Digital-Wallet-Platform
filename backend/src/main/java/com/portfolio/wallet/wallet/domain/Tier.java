package com.portfolio.wallet.wallet.domain;

import java.util.Objects;

/**
 * Wallet tier; selects the transfer daily limit (documentations/06 MVP-ADR-18; doc 08 Q9). Premium
 * is not offered (doc 06 §3, D-05).
 */
public enum Tier {
  BASIC,
  VERIFIED;

  /**
   * Maps an approved KYC level to the tier: {@code BASIC} → {@link #BASIC}, {@code FULL} → {@link
   * #VERIFIED} (doc 01 §7.5; doc 08 Q9).
   *
   * @throws IllegalArgumentException for any other level
   */
  public static Tier forKycLevel(String kycLevel) {
    Objects.requireNonNull(kycLevel, "kycLevel");
    return switch (kycLevel) {
      case "BASIC" -> BASIC;
      case "FULL" -> VERIFIED;
      default -> throw new IllegalArgumentException("unknown KYC level");
    };
  }
}
