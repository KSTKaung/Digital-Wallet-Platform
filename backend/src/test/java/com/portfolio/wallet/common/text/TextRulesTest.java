package com.portfolio.wallet.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The shared free-text rules (documentations/06 MVP-ADR-10; moved from {@code user.domain.KycText}
 * in PR 5a step 3). The KYC tests ({@code KycValuesTest}, {@code KycApplicationTest}) still cover
 * the same rules through the user module; these pin them where they now live.
 */
class TextRulesTest {

  @Test
  void normalisesToNfcAndStrips() {
    assertThat(TextRules.require("f", "  Café\t", 10)).isEqualTo("Café");
  }

  @Test
  void countsCodePointsAfterNormalising() {
    // 3 code points after NFC (e + combining acute is one), within a limit of 3.
    assertThat(TextRules.require("f", "aéb", 3)).isEqualTo("aéb");
    assertThat(TextRules.require("f", "😀😀", 2)).hasSize(4);
    assertThatThrownBy(() -> TextRules.require("f", "abcd", 3))
        .isInstanceOf(InvalidTextException.class)
        .hasMessage("f: must have at most 3 characters");
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", " ", " ", "\n"})
  void blankIsRefused(String value) {
    assertThatThrownBy(() -> TextRules.require("f", value, 10))
        .isInstanceOf(InvalidTextException.class)
        .hasMessage("f: must not be blank");
  }

  @Test
  void nullIsRefused() {
    assertThatThrownBy(() -> TextRules.require("f", null, 10))
        .isInstanceOf(InvalidTextException.class)
        .satisfies(
            e -> {
              InvalidTextException invalid = (InvalidTextException) e;
              assertThat(invalid.field()).isEqualTo("f");
              assertThat(invalid.rule()).isEqualTo("must not be blank");
            });
  }

  @ParameterizedTest(name = "U+{0}")
  @ValueSource(strings = {"0007", "007F", "200B", "200C", "200D", "202E", "FEFF", "D800"})
  void controlFormatAndSurrogateCharactersAreRefused(String hex) {
    String value = "a" + (char) Integer.parseInt(hex, 16) + "b";

    assertThatThrownBy(() -> TextRules.require("f", value, 10))
        .isInstanceOf(InvalidTextException.class)
        .hasMessage("f: must not contain control or format characters");
  }

  @Test
  void joinersAreAllowedOnlyWhenAsked() {
    assertThat(TextRules.requireAllowingJoiners("f", "a‌b‍c", 10)).isEqualTo("a‌b‍c");
    assertThatThrownBy(() -> TextRules.requireAllowingJoiners("f", "a​b", 10))
        .isInstanceOf(InvalidTextException.class);
  }

  @Test
  void aReasonNeedsALetterOrADigit() {
    assertThat(TextRules.requireReason("reason", "Item 7", 10)).isEqualTo("Item 7");
    assertThat(TextRules.requireReason("reason", "7", 10)).isEqualTo("7");
    assertThat(TextRules.requireReason("reason", "ก", 10)).isEqualTo("ก");
    assertThatThrownBy(() -> TextRules.requireReason("reason", "-- !!", 10))
        .isInstanceOf(InvalidTextException.class)
        .hasMessage("reason: must contain a letter or a digit");
  }
}
