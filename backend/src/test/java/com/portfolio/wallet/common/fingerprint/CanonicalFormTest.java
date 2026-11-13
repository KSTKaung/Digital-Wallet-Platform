package com.portfolio.wallet.common.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The shared hashing core of request fingerprints (documentations/02 §1.4; doc 07 §7). */
class CanonicalFormTest {

  @Test
  void writesVersionFixedAndTextLinesInOrder() {
    String form =
        CanonicalForm.version("example-idempotency-v1")
            .fixed("operation", "DEPOSIT")
            .text("reference", "rent")
            .text("note", null)
            .text("empty", "")
            .build();

    assertThat(form)
        .isEqualTo(
            "example-idempotency-v1\n"
                + "operation=DEPOSIT\n"
                + "reference=4:rent\n"
                + "note=null\n"
                + "empty=0:\n");
  }

  @Test
  void textLengthIsInUtf16UnitsAndKeepsLineBreaksUnambiguous() {
    assertThat(CanonicalForm.version("x-v1").text("note", "ก\nb=1").build())
        .isEqualTo("x-v1\nnote=5:ก\nb=1\n");
    String oneField = CanonicalForm.version("x-v1").text("a", "x\nb=1:y").build();
    String twoFields = CanonicalForm.version("x-v1").text("a", "x").text("b", "y").build();
    assertThat(CanonicalForm.sha256Hex(oneField)).isNotEqualTo(CanonicalForm.sha256Hex(twoFields));
  }

  @Test
  void sha256IsTheStandardDigestInLowerCaseHex() {
    // FIPS 180-2 test vector.
    assertThat(CanonicalForm.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(CanonicalForm.sha256Hex("ก")).matches("[0-9a-f]{64}");
    assertThatThrownBy(() -> CanonicalForm.sha256Hex(null))
        .isInstanceOf(NullPointerException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "v1", "wallet-idempotency", "Wallet-idempotency-v1", "x-v1\n"})
  void refusesAnInvalidVersionLine(String version) {
    assertThatThrownBy(() -> CanonicalForm.version(version))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Reference", "1st", "a=b", "a b", "a\nb"})
  void refusesInvalidFieldNames(String name) {
    CanonicalForm.Builder builder = CanonicalForm.version("x-v1");
    assertThatThrownBy(() -> builder.text(name, "x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.fixed(name, "x")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAFieldAddedTwiceAsTextOrFixed() {
    CanonicalForm.Builder builder = CanonicalForm.version("x-v1").fixed("amount", "1.00");
    assertThatThrownBy(() -> builder.fixed("amount", "2.00"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.text("amount", "2.00"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFixedFieldHasAValueWithoutLineBreaks() {
    CanonicalForm.Builder builder = CanonicalForm.version("x-v1");
    assertThatThrownBy(() -> builder.fixed("a", null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> builder.fixed("b", "1\n2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.fixed("c", "1\r2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.text(null, "x")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> CanonicalForm.version(null)).isInstanceOf(NullPointerException.class);
  }
}
