package com.portfolio.wallet.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** documentations/08 Q3, §4.5 test case 10 (wallet-number check digit). */
class WalletNumberTest {

  /** The classic Luhn test vector; it happens to be 11 digits long. */
  private static final String KNOWN_VALID = "79927398713";

  @Test
  void acceptsAKnownValidLuhnNumber() {
    assertThat(WalletNumber.parse(KNOWN_VALID).value()).isEqualTo(KNOWN_VALID);
    assertThat(WalletNumber.checkDigit("7992739871")).isEqualTo(3);
  }

  @Test
  void rejectsEveryWrongCheckDigit() {
    for (char digit = '0'; digit <= '9'; digit++) {
      if (digit == '3') {
        continue;
      }
      String wrong = KNOWN_VALID.substring(0, 10) + digit;
      assertThatThrownBy(() -> WalletNumber.parse(wrong))
          .isInstanceOf(InvalidWalletNumberException.class)
          .hasMessage("wallet number check digit is invalid");
    }
  }

  @Test
  void detectsASingleDigitTypo() {
    // Luhn detects every single-digit error: the fifth digit 7 is changed to 8.
    assertThatThrownBy(() -> WalletNumber.parse("79928398713"))
        .isInstanceOf(InvalidWalletNumberException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"7992739871", "799273987130", "7992739871a", " 9927398713", "7992739871٣"})
  void rejectsWrongLengthOrNonAsciiDigits(String text) {
    assertThatThrownBy(() -> WalletNumber.parse(text))
        .isInstanceOf(InvalidWalletNumberException.class)
        .hasMessage("wallet number must be 11 digits");
  }

  @Test
  void generatedNumbersAreElevenDigitsAndValidate() {
    RandomGenerator random = new SplittableRandom(42);
    for (int i = 0; i < 1_000; i++) {
      WalletNumber generated = WalletNumber.generate(random);

      assertThat(generated.value()).hasSize(WalletNumber.LENGTH).containsOnlyDigits();
      assertThat(WalletNumber.parse(generated.value())).isEqualTo(generated);
    }
  }

  @Test
  void generationUsesTenRandomDigitsThenTheCheckDigit() {
    RandomGenerator zeros =
        new RandomGenerator() {
          @Override
          public long nextLong() {
            return 0;
          }

          @Override
          public int nextInt(int bound) {
            return 0;
          }
        };

    assertThat(WalletNumber.generate(zeros).value()).isEqualTo("00000000000");
  }

  /** Owner, 2026-11-04, X2: format and check digit are two separate steps. */
  @Test
  void formatAndCheckDigitAreSeparateSteps() {
    assertThat(WalletNumber.hasValidFormat(KNOWN_VALID)).isTrue();
    assertThat(WalletNumber.hasValidCheckDigit(KNOWN_VALID)).isTrue();

    String wrongDigit = "79927398714";
    assertThat(WalletNumber.hasValidFormat(wrongDigit)).isTrue();
    assertThat(WalletNumber.hasValidCheckDigit(wrongDigit)).isFalse();

    for (String bad : new String[] {null, "", "7992739871", "799273987130", "7992739871a", "７９"}) {
      assertThat(WalletNumber.hasValidFormat(bad)).as(String.valueOf(bad)).isFalse();
    }
    assertThatThrownBy(() -> WalletNumber.hasValidCheckDigit("7992739871"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WalletNumber.hasValidCheckDigit(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void valueSemantics() {
    assertThat(WalletNumber.parse(KNOWN_VALID))
        .isEqualTo(WalletNumber.parse(KNOWN_VALID))
        .hasSameHashCodeAs(WalletNumber.parse(KNOWN_VALID))
        .hasToString(KNOWN_VALID);
  }
}
