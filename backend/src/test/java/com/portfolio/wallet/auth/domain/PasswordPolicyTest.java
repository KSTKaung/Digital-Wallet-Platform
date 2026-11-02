package com.portfolio.wallet.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy(12, 128, 5);

  @ParameterizedTest
  @CsvSource({"11,false", "12,true", "128,true", "129,false"})
  void lengthBoundaries(int length, boolean valid) {
    assertThat(policy.isValidLength("x".repeat(length))).isEqualTo(valid);
    assertThat(policy.isOverMaximum("x".repeat(length))).isEqualTo(length > 128);
  }

  @Test
  void lengthIsInCodePointsNotCharsOrBytes() {
    String emoji = new String(Character.toChars(0x1F600));
    assertThat(emoji.repeat(12)).hasSize(24);
    assertThat(policy.isValidLength(emoji.repeat(12))).isTrue();
    assertThat(policy.isValidLength(emoji.repeat(128))).isTrue();
    assertThat(policy.isValidLength(emoji.repeat(11))).isFalse();
  }

  @Test
  void lengthIsCountedAfterNfc() {
    String decomposed = "é";
    assertThat(policy.isValidLength(decomposed.repeat(7))).isFalse();
    assertThat(PasswordPolicy.normalize(decomposed)).isEqualTo("é");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void eachOfTheLastFiveCountsAsReuse(int index) {
    List<String> history = List.of("h0", "h1", "h2", "h3", "h4");
    assertThat(policy.reusesRecent(history, ("h" + index)::equals)).isTrue();
  }

  @Test
  void aPasswordOlderThanTheDepthIsNotReuse() {
    List<String> history = new ArrayList<>(List.of("h0", "h1", "h2", "h3", "h4", "h5"));
    assertThat(policy.reusesRecent(history, "h5"::equals)).isFalse();
  }
}
