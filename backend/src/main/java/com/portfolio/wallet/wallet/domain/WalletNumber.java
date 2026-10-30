package com.portfolio.wallet.wallet.domain;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Public wallet number: {@value #RANDOM_DIGITS} random digits followed by a Luhn check digit,
 * {@value #LENGTH} digits in total (documentations/08 Q3; doc 06 MVP-ADR-20; V3 {@code
 * ck_wallets_wallet_number}). Immutable once assigned.
 *
 * <p>Generation takes the random source as a parameter; production code must pass a
 * cryptographically strong one (for example {@code SecureRandom}), because numbers must not be
 * guessable (MVP-ADR-20). Uniqueness is guaranteed by the database ({@code
 * uq_wallets_wallet_number}), not here.
 */
public final class WalletNumber {

  /** Total length: 10 random digits plus the check digit (doc 08 Q3). */
  public static final int LENGTH = 11;

  /** Random digits before the check digit (doc 08 Q3; MVP-ADR-20: at least 10). */
  public static final int RANDOM_DIGITS = LENGTH - 1;

  private final String value;

  private WalletNumber(String value) {
    this.value = value;
  }

  /** Generates a new number: 10 random digits plus the Luhn check digit. */
  public static WalletNumber generate(RandomGenerator random) {
    Objects.requireNonNull(random, "random");
    StringBuilder digits = new StringBuilder(LENGTH);
    for (int i = 0; i < RANDOM_DIGITS; i++) {
      digits.append((char) ('0' + random.nextInt(10)));
    }
    digits.append((char) ('0' + checkDigit(digits)));
    return new WalletNumber(digits.toString());
  }

  /**
   * Validates and wraps a wallet number.
   *
   * @throws InvalidWalletNumberException unless the text is exactly 11 ASCII digits with a valid
   *     Luhn check digit
   */
  public static WalletNumber parse(String text) {
    if (text == null || text.length() != LENGTH) {
      throw new InvalidWalletNumberException("wallet number must be 11 digits");
    }
    for (int i = 0; i < LENGTH; i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        throw new InvalidWalletNumberException("wallet number must be 11 digits");
      }
    }
    if (!hasValidCheckDigit(text)) {
      throw new InvalidWalletNumberException("wallet number check digit is invalid");
    }
    return new WalletNumber(text);
  }

  public String value() {
    return value;
  }

  /** Luhn check digit for a string of ASCII digits (the payload without its check digit). */
  static int checkDigit(CharSequence payload) {
    int sum = 0;
    // The rightmost payload digit is doubled, then every second digit to its left.
    for (int i = payload.length() - 1, position = 0; i >= 0; i--, position++) {
      sum += luhnTerm(payload.charAt(i) - '0', position % 2 == 0);
    }
    return (10 - sum % 10) % 10;
  }

  /** Luhn validation of a complete number (payload plus check digit). */
  static boolean hasValidCheckDigit(CharSequence number) {
    int sum = 0;
    // The check digit itself is not doubled; every second digit to its left is.
    for (int i = number.length() - 1, position = 0; i >= 0; i--, position++) {
      sum += luhnTerm(number.charAt(i) - '0', position % 2 == 1);
    }
    return sum % 10 == 0;
  }

  private static int luhnTerm(int digit, boolean doubled) {
    if (!doubled) {
      return digit;
    }
    int twice = digit * 2;
    return twice > 9 ? twice - 9 : twice;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof WalletNumber other && value.equals(other.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
