package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The {@code description} rules of {@code POST /transfers} (doc 08 Q4; owner, 2026-11-11): at most
 * 140 code points, as PostgreSQL {@code varchar(140)} counts them (not UTF-16 units, not grapheme
 * clusters), and no control, surrogate or format characters (J6).
 */
class CreateTransferRequestTest {

  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  /** U+1F600: one code point, two UTF-16 units. */
  static final String GRIN = "😀";

  /** Regional indicators T and H (category So): one grapheme, two code points. */
  static final String THAI_FLAG = "🇹🇭";

  /** Man, ZWJ, woman, ZWJ, girl: one grapheme, five code points, two of them U+200D (Cf). */
  static final String ZWJ_FAMILY = "👨‍👩‍👧";

  @AfterAll
  static void close() {
    FACTORY.close();
  }

  private static List<String> violations(String description) {
    return VALIDATOR
        .validate(
            new CreateTransferRequest("01234567895", new BigDecimal("10.00"), "THB", description))
        .stream()
        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
        .toList();
  }

  @Test
  void nonBmpCharactersCountOnceEach() {
    assertThat(GRIN.repeat(140)).hasSize(280);
    assertThat(violations(GRIN.repeat(140))).isEmpty();
    assertThat(violations(GRIN.repeat(141)))
        .containsExactly("description: " + RequestText.AT_MOST_140_CHARACTERS_MESSAGE);
  }

  @Test
  void aMultiCodePointEmojiCountsAsItsCodePoints() {
    assertThat(violations(THAI_FLAG.repeat(70))).isEmpty();
    assertThat(violations(THAI_FLAG.repeat(70) + "a"))
        .containsExactly("description: " + RequestText.AT_MOST_140_CHARACTERS_MESSAGE);
  }

  @Test
  void aZwjSequenceIsRefusedAsAFormatCharacterNotByLength() {
    assertThat(violations(ZWJ_FAMILY))
        .containsExactly("description: " + RequestText.NO_CONTROL_CHARACTERS_MESSAGE);
  }

  @Test
  void aLoneSurrogateIsRefused() {
    assertThat(violations("a\uD83Db"))
        .containsExactly("description: " + RequestText.NO_CONTROL_CHARACTERS_MESSAGE);
  }

  @Test
  void emptyAndSpacedDescriptionsAreValid() {
    assertThat(violations("")).isEmpty();
    assertThat(violations(" rent ")).isEmpty();
    assertThat(violations(null)).isEmpty();
  }

  @Test
  void printsNoValue() {
    assertThat(new CreateTransferRequest("01234567895", BigDecimal.TEN, "THB", "secret").toString())
        .doesNotContain("secret")
        .doesNotContain("01234567895");
  }
}
