package com.portfolio.wallet.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Legal name, document reference, masking and profile rules (doc 08 §4B.2 Q5, Q6, Q15). */
class KycValuesTest {

  // Legal name -----------------------------------------------------------------------------------

  @Test
  void aLegalNameIsStrippedAndNormalised() {
    // "e" followed by a combining acute accent becomes the single NFC character.
    LegalName name = new LegalName("  René ", "\tDupont ");

    assertThat(name.firstName()).isEqualTo("René");
    assertThat(name.lastName()).isEqualTo("Dupont");
  }

  @Test
  void aNameHasAtMost100CharactersCountedAsCodePoints() {
    new LegalName("a".repeat(100), "b");
    // 100 letters outside the BMP are 200 UTF-16 units but still fit varchar(100).
    new LegalName("\uD801\uDC37".repeat(100), "b");

    assertThatThrownBy(() -> new LegalName("a".repeat(101), "b"))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("firstName: must have at most 100 characters");
    assertThatThrownBy(() -> new LegalName("a", "b".repeat(101)))
        .isInstanceOf(InvalidKycDetailsException.class)
        .extracting(e -> ((InvalidKycDetailsException) e).field())
        .isEqualTo("lastName");
  }

  @ParameterizedTest(name = "[{index}]")
  @ValueSource(strings = {"", "   ", "a\u0000b", "a\nb", "\uD800"})
  void aNameMustBeTextWithoutControlCharacters(String value) {
    assertThatThrownBy(() -> new LegalName(value, "Jaidee"))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessageStartingWith("firstName: ")
        .hasMessageNotContaining(value.isBlank() ? "\u0001" : value);
  }

  @Test
  void aMissingNameIsRefused() {
    assertThatThrownBy(() -> new LegalName(null, "Jaidee"))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("firstName: must not be blank");
  }

  @Test
  void theMaskedNameIsTheFirstNameAndTheLastInitial() {
    assertThat(new LegalName("Somchai", "Jaidee").masked()).isEqualTo("Somchai J.");
    assertThat(new LegalName("สมชาย", "ใจดี").masked()).isEqualTo("สมชาย ใ.");
    assertThat(new LegalName("Ann", "\uD83D\uDE00x").masked()).isEqualTo("Ann x.");
  }

  @Test
  void aLegalNameDoesNotPrintItself() {
    assertThat(new LegalName("Somchai", "Jaidee").toString())
        .isEqualTo("LegalName[]")
        .doesNotContain("Somchai");
  }

  // Document reference ---------------------------------------------------------------------------

  @Test
  void aReferenceIsStrippedAndHasAtMost50Characters() {
    assertThat(new DocumentReference(" AB-123 ").value()).isEqualTo("AB-123");
    new DocumentReference("x".repeat(50));

    assertThatThrownBy(() -> new DocumentReference("x".repeat(51)))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("documentReference: must have at most 50 characters");
    assertThatThrownBy(() -> new DocumentReference(" "))
        .hasMessage("documentReference: must not be blank");
    assertThatThrownBy(() -> new DocumentReference("AB\u0007CD"))
        .hasMessage("documentReference: must not contain control or format characters");
  }

  /** J6: invisible format characters are refused, and the message never repeats the value. */
  @ParameterizedTest
  @ValueSource(strings = {"AB\u200B123", "\u202EAB123", "AB\u2066123", "AB\uFEFF1", "AB\u200E1"})
  void formatCharactersAreRefusedInEveryKycText(String value) {
    assertThatThrownBy(() -> new DocumentReference(value))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("documentReference: must not contain control or format characters");
    assertThatThrownBy(() -> new LegalName(value, "Jaidee"))
        .hasMessage("firstName: must not contain control or format characters");
    assertThatThrownBy(() -> new LegalName("Somchai", value))
        .hasMessage("lastName: must not contain control or format characters");
  }

  // R2: a legal name needs a letter; joiners only between letters or marks -------------------

  @ParameterizedTest
  @ValueSource(strings = {"123", "--", "'.'", "\uD83D\uDE00", "1\u0301"})
  void aLegalNameWithoutALetterIsRefused(String value) {
    assertThatThrownBy(() -> new LegalName(value, "Jaidee"))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("firstName: must contain a letter");
    assertThatThrownBy(() -> new LegalName("Somchai", value))
        .hasMessage("lastName: must contain a letter");
  }

  /** Letters outside the Basic Multilingual Plane count: the check runs over code points. */
  @Test
  void aLetterOutsideTheBmpCounts() {
    assertThat(new LegalName("\uD801\uDC37", "\uD801\uDC37").firstName()).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\u200CMehrdad",
        "Mehrdad\u200C",
        "Mehr\u200C\u200Ddad",
        "Mehr\u200D\u200Ddad",
        "1\u200Ca",
        "a \u200Cb",
        "a-\u200Db"
      })
  void aJoinerOutsideTwoLettersOrMarksIsRefused(String value) {
    assertThatThrownBy(() -> new LegalName(value, "Jaidee"))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("firstName: may contain a zero-width joiner or non-joiner only between letters")
        .hasMessageNotContaining(value);
  }

  /** Between letters, or after a combining mark (Devanagari virama, Mn), as names need. */
  @ParameterizedTest
  @ValueSource(strings = {"Mehr\u200Cdad", "\u0915\u094D\u200D\u0937", "x\u0301\u200Cb"})
  void aJoinerBetweenLettersOrMarksIsAccepted(String value) {
    assertThat(new LegalName(value, value).firstName()).isEqualTo(value);
  }

  /** R2: the masked initial of the last name is always a letter. */
  @Test
  void theMaskedInitialIsTheLastNamesFirstLetter() {
    assertThat(new LegalName("Somchai", "'OBrien").masked()).isEqualTo("Somchai O.");
    assertThat(new LegalName("Somchai", "-Jaidee").masked()).isEqualTo("Somchai J.");
    assertThat(new LegalName("Ann", "1x").masked()).isEqualTo("Ann x.");
    assertThat(new LegalName("Ann", "\uD801\uDC37x").masked()).isEqualTo("Ann \uD801\uDC37.");
  }

  /**
   * P1: zero-width non-joiner and joiner are part of correct spelling in several scripts, so legal
   * names accept them; nothing else does.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\u200C", "\u200D"})
  void joinerCharactersAreAllowedInLegalNamesOnly(String joiner) {
    String name = "Mehr" + joiner + "dad";

    LegalName legalName = new LegalName(name, "Kar" + joiner + "imi");
    assertThat(legalName.firstName()).isEqualTo(name);
    assertThat(legalName.lastName()).contains(joiner);
    assertThatThrownBy(() -> new DocumentReference("AB" + joiner + "1234567"))
        .hasMessage("documentReference: must not contain control or format characters");
    KycApplication pending =
        KycApplication.restore(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            KycApplicationTest.details("AB1234567"),
            KycStatus.PENDING_REVIEW,
            null,
            null);
    assertThatThrownBy(
            () -> pending.reject(java.util.UUID.randomUUID(), "Photo" + joiner + "blurred"))
        .hasMessage("reason: must not contain control or format characters");
  }

  /** K5: the last 4 show only when at least 4 others stay hidden (8 characters or more). */
  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "AB1234567, ***4567",
    "12345678, ***5678",
    "1234567, ***",
    "12345, ***",
    "1234, ***",
    "1, ***",
  })
  void aReferenceShowsItsLastFourOnlyWhenFourStayHidden(String reference, String masked) {
    assertThat(new DocumentReference(reference).masked()).isEqualTo(masked);
  }

  @Test
  void theDocumentMaskCountsCharactersNotCodeUnits() {
    // 7 characters in 14 UTF-16 units: fully masked.
    assertThat(Masking.documentReference("😀😀😀😀😀😀😀")).isEqualTo(Masking.MASK);
    assertThat(Masking.documentReference("😀😀😀😀ABCD")).isEqualTo("***ABCD");
    assertThat(Masking.documentReference(null)).isNull();
  }

  @Test
  void aReferenceDoesNotPrintItselfAndIsFoundInTextIgnoringCase() {
    DocumentReference reference = new DocumentReference("AB1234567");

    assertThat(reference.toString()).isEqualTo("DocumentReference[]");
    assertThat(reference.appearsIn("see ab1234567")).isTrue();
    assertThat(reference.appearsIn("see AB123")).isFalse();
    assertThat(reference.appearsIn(null)).isFalse();
  }

  /** Case, whitespace, separators and full-width forms do not hide the reference (Q13). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "AB1234567",
        "see ab1234567.",
        "AB 123 4567",
        "ab-123-4567",
        "A/B.1_2,3:4;5(6)7",
        "AB\t1234\u00A0567",
        "ＡＢ１２３４５６７",
        "prefixAB1234567suffix"
      })
  void aReferenceIsFoundWhateverItsSpelling(String text) {
    assertThat(new DocumentReference("AB-1234567").appearsIn(text)).isTrue();
    assertThat(new DocumentReference("AB1234567").appearsIn(text)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"AB123456", "B1234567", "AB 1234 568", "document unreadable"})
  void otherTextIsNotTheReference(String text) {
    assertThat(new DocumentReference("AB1234567").appearsIn(text)).isFalse();
  }

  @Test
  void aReferenceWithoutLettersOrDigitsIsComparedAsItIs() {
    DocumentReference reference = new DocumentReference("--//--");

    assertThat(reference.appearsIn("see --//-- here")).isTrue();
    assertThat(reference.appearsIn("see -- // -- here")).isFalse();
    assertThat(reference.appearsIn("any reason at all")).isFalse();
  }

  @Test
  void theComparableFormKeepsOnlyLowerCaseLettersAndDigits() {
    assertThat(DocumentReference.comparable("Ab-12 ３/๑")).isEqualTo("ab123๑");
  }

  // Masking --------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "alice@example.com, a***@example.com",
    "a@example.com, a***@example.com",
    "no-at-sign, ***",
    "@example.com, ***",
  })
  void anEmailKeepsItsFirstCharacterAndDomain(String email, String masked) {
    assertThat(Masking.email(email)).isEqualTo(masked);
  }

  @Test
  void anEmailStartingWithASurrogatePairKeepsTheWholeCharacter() {
    assertThat(Masking.email("😀x@example.com")).isEqualTo("😀***@example.com");
    assertThat(Masking.email(null)).isNull();
  }

  @Test
  void aPhoneShowsOnlyItsLastFourDigits() {
    assertThat(Masking.phone("+66812345678")).isEqualTo("***5678");
    assertThat(Masking.phone(null)).isNull();
    assertThat(Masking.lastFour(null)).isNull();
  }

  // Profile rules --------------------------------------------------------------------------------

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

  @Test
  void aDateOfBirthMayBeTodayButNotInTheFuture() {
    assertThat(CustomerProfileRules.requireDateOfBirth(TODAY.minusDays(1), TODAY))
        .isEqualTo(TODAY.minusDays(1));
    assertThat(CustomerProfileRules.requireDateOfBirth(TODAY, TODAY)).isEqualTo(TODAY);

    assertThatThrownBy(() -> CustomerProfileRules.requireDateOfBirth(TODAY.plusDays(1), TODAY))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("dateOfBirth: must not be in the future");
    assertThatThrownBy(() -> CustomerProfileRules.requireDateOfBirth(null, TODAY))
        .hasMessage("dateOfBirth: must not be null");
  }

  @ParameterizedTest
  @ValueSource(strings = {"TH", "US", "GB"})
  void anIsoCountryCodeIsAccepted(String code) {
    assertThat(CustomerProfileRules.requireCountryCode(code)).isEqualTo(code);
  }

  @ParameterizedTest
  @ValueSource(strings = {"th", "XX", "THA", "T", ""})
  void anythingButAnIsoAlpha2CodeIsRefused(String code) {
    assertThatThrownBy(() -> CustomerProfileRules.requireCountryCode(code))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("countryCode: must be an ISO 3166-1 alpha-2 code");
  }

  @Test
  void aMissingCountryCodeIsRefused() {
    assertThatThrownBy(() -> CustomerProfileRules.requireCountryCode(null))
        .isInstanceOf(InvalidKycDetailsException.class);
  }
}
