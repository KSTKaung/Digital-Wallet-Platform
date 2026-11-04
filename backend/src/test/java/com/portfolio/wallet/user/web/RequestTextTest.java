package com.portfolio.wallet.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.user.domain.LegalName;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The request-level text rules (J6, P1, R2, T3): legal names are checked by {@link LegalNamePart},
 * which runs the domain's rule, so the request layer and the domain agree on every case.
 */
class RequestTextTest {

  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  @AfterAll
  static void close() {
    FACTORY.close();
  }

  private static Set<ConstraintViolation<KycApplicationRequest>> firstName(String value) {
    return VALIDATOR.validate(
        new KycApplicationRequest("BASIC", "ID_CARD", "AB1234567", value, "Jaidee"));
  }

  private static boolean domainAccepts(String value) {
    try {
      LegalName.requirePart("firstName", value);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static List<String> messages(Set<ConstraintViolation<KycApplicationRequest>> found) {
    return found.stream().map(ConstraintViolation::getMessage).toList();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Mehr\u200Cdad",
        "Mehr\u200Ddad",
        "\u0915\u094D\u200D\u0937",
        "x\u0301\u200Cb",
        "\uD801\uDC00\u200Cb",
        "\uD801\uDC37",
        "1x",
        "สมชาย"
      })
  void namesTheDomainAcceptsAreAcceptedHere(String value) {
    assertThat(domainAccepts(value)).isTrue();
    assertThat(firstName(value)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\u200Cab",
        "ab\u200C",
        "a\u200C\u200Db",
        "1\u200Ca",
        "a \u200Db",
        "\uD83D\uDE00\u200Cb",
        "Mehr\u200Bdad",
        "Mehr\u202Edad",
        "Mehr\uFEFFdad",
        "123",
        "--"
      })
  void namesTheDomainRefusesAreRefusedHereWithoutEchoingThem(String value) {
    assertThat(domainAccepts(value)).isFalse();
    Set<ConstraintViolation<KycApplicationRequest>> found = firstName(value);
    assertThat(found).isNotEmpty();
    assertThat(found).allSatisfy(v -> assertThat(v.getPropertyPath()).hasToString("firstName"));
    assertThat(messages(found)).allSatisfy(m -> assertThat(m).doesNotContain(value));
  }

  @Test
  void theMessagesAreTheDomainsRules() {
    assertThat(messages(firstName("123"))).containsExactly("must contain a letter");
    assertThat(messages(firstName("\u200Cab")))
        .containsExactly("may contain a zero-width joiner or non-joiner only between letters");
    assertThat(messages(firstName("Mehr\u200Bdad")))
        .containsExactly("must not contain control or format characters");
  }

  /** T3: very long input is refused by size, in one linear pass, without a stack overflow. */
  @Test
  void aVeryLongNameIsRefusedBySizeWithoutAnException() {
    String plain = "a".repeat(100_000);
    String joined = "a\u200C".repeat(50_000) + "b";

    for (String value : List.of(plain, joined)) {
      assertThatCode(() -> firstName(value)).doesNotThrowAnyException();
      assertThat(messages(firstName(value))).contains("size must be between 0 and 100");
    }
  }

  @Test
  void otherFieldsKeepTheirCharacterClassPatterns() {
    for (String joiner : new String[] {"\u200C", "\u200D"}) {
      assertThat("AB" + joiner + "123").doesNotMatch(RequestText.NO_CONTROL_CHARACTERS);
    }
    assertThat("AB-123 4567").matches(RequestText.NO_CONTROL_CHARACTERS);
    assertThatThrownBy(() -> LegalName.requirePart("lastName", "\u200Cx"))
        .hasMessage("lastName: may contain a zero-width joiner or non-joiner only between letters");
  }
}
