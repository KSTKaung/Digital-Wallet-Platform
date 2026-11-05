package com.portfolio.wallet.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestFingerprintTest {

  private static final UUID CUSTOMER = UUID.fromString("0b0e5f3a-1c2d-4e5f-8a9b-0c1d2e3f4a5b");

  private static RequestFingerprint deposit(String amount, String reference) {
    return RequestFingerprint.builder(
            Operation.DEPOSIT, CUSTOMER, Money.of(new BigDecimal(amount), Money.THB))
        .field("reference", reference)
        .build();
  }

  @Test
  void canonicalFormIsDocumentedVersionOne() {
    assertThat(deposit("10.5", "rent").canonicalForm())
        .isEqualTo(
            "wallet-idempotency-v1\n"
                + "operation=DEPOSIT\n"
                + "customer=0b0e5f3a-1c2d-4e5f-8a9b-0c1d2e3f4a5b\n"
                + "amount=10.50\n"
                + "currency=THB\n"
                + "reference=4:rent\n");
  }

  @Test
  void hashIsSha256OfTheCanonicalFormInLowerCaseHex() {
    RequestFingerprint fingerprint = deposit("10.00", "rent");
    assertThat(fingerprint.sha256Hex()).matches("[0-9a-f]{64}");
    assertThat(deposit("10.00", "rent").sha256Hex()).isEqualTo(fingerprint.sha256Hex());
  }

  @Test
  void hashMatchesAnIndependentSha256() throws Exception {
    RequestFingerprint fingerprint = deposit("10.00", "rent");
    byte[] digest =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.canonicalForm().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(fingerprint.sha256Hex()).isEqualTo(java.util.HexFormat.of().formatHex(digest));
  }

  @ParameterizedTest
  @ValueSource(strings = {"10", "10.0", "10.00", "10.000", "1E+1", "10.0000"})
  void trailingZerosAndFormatGiveTheSameHash(String amount) {
    assertThat(deposit(amount, "rent").sha256Hex()).isEqualTo(deposit("10.00", "rent").sha256Hex());
  }

  @Test
  void aThirdDecimalIsRefusedUpstreamNotRounded() {
    assertThatThrownBy(() -> deposit("10.001", "rent")).isInstanceOf(InvalidAmountException.class);
  }

  @Test
  void everyFieldChangesTheHash() {
    String base = deposit("10.00", "rent").sha256Hex();

    assertThat(deposit("10.01", "rent").sha256Hex()).isNotEqualTo(base);
    assertThat(deposit("10.00", "Rent").sha256Hex()).isNotEqualTo(base);
    assertThat(deposit("10.00", null).sha256Hex()).isNotEqualTo(base);
    assertThat(
            RequestFingerprint.builder(
                    Operation.WITHDRAWAL, CUSTOMER, Money.of(new BigDecimal("10.00"), Money.THB))
                .field("reference", "rent")
                .build()
                .sha256Hex())
        .isNotEqualTo(base);
    assertThat(
            RequestFingerprint.builder(
                    Operation.DEPOSIT, UUID.randomUUID(), Money.of(new BigDecimal("10"), Money.THB))
                .field("reference", "rent")
                .build()
                .sha256Hex())
        .isNotEqualTo(base);
  }

  @Test
  void nullAndEmptyValuesDiffer() {
    assertThat(deposit("10.00", null).canonicalForm()).endsWith("reference=null\n");
    assertThat(deposit("10.00", "").canonicalForm()).endsWith("reference=0:\n");
    assertThat(deposit("10.00", null).sha256Hex()).isNotEqualTo(deposit("10.00", "").sha256Hex());
    // The text "null" is a value, not the absence of one.
    assertThat(deposit("10.00", "null").sha256Hex())
        .isNotEqualTo(deposit("10.00", null).sha256Hex());
  }

  @Test
  void lengthPrefixKeepsValuesWithLineBreaksUnambiguous() {
    Money amount = Money.of(new BigDecimal("10"), Money.THB);
    RequestFingerprint oneField =
        RequestFingerprint.builder(Operation.DEPOSIT, CUSTOMER, amount)
            .field("reference", "a\nnote=1:b")
            .build();
    RequestFingerprint twoFields =
        RequestFingerprint.builder(Operation.DEPOSIT, CUSTOMER, amount)
            .field("reference", "a")
            .field("note", "b")
            .build();

    assertThat(oneField.sha256Hex()).isNotEqualTo(twoFields.sha256Hex());
  }

  @Test
  void exposesOperationAndCustomer() {
    RequestFingerprint fingerprint = deposit("10", null);
    assertThat(fingerprint.operation()).isEqualTo(Operation.DEPOSIT);
    assertThat(fingerprint.customerId()).isEqualTo(CUSTOMER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Reference", "1st", "amount", "currency", "operation", "customer"})
  void refusesInvalidOrReservedFieldNames(String name) {
    RequestFingerprint.Builder builder =
        RequestFingerprint.builder(
            Operation.DEPOSIT, CUSTOMER, Money.of(new BigDecimal("10"), Money.THB));
    assertThatThrownBy(() -> builder.field(name, "x")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAFieldAddedTwice() {
    RequestFingerprint.Builder builder =
        RequestFingerprint.builder(
                Operation.DEPOSIT, CUSTOMER, Money.of(new BigDecimal("10"), Money.THB))
            .field("reference", "a");
    assertThatThrownBy(() -> builder.field("reference", "b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresOperationCustomerAndAmount() {
    Money amount = Money.of(new BigDecimal("10"), Money.THB);
    assertThatThrownBy(() -> RequestFingerprint.builder(null, CUSTOMER, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RequestFingerprint.builder(Operation.DEPOSIT, null, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RequestFingerprint.builder(Operation.DEPOSIT, CUSTOMER, null))
        .isInstanceOf(NullPointerException.class);
  }
}
