package com.portfolio.wallet.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The request hash of {@code POST /transfers} (documentations/02 §1.4; doc 08 §4C.2 Q3). */
class TransferRequestFingerprintTest {

  private static final UUID SENDER = UUID.fromString("0b0e5f3a-1c2d-4e5f-8a9b-0c1d2e3f4a5b");
  private static final String RECEIVER = "01234567895";

  private static TransferRequestFingerprint transfer(
      String receiver, String amount, String description) {
    return TransferRequestFingerprint.builder(
            SENDER, receiver, Money.of(new BigDecimal(amount), Money.THB))
        .description(description)
        .build();
  }

  @Test
  void canonicalFormIsDocumentedVersionOne() {
    assertThat(transfer(RECEIVER, "10.5", "rent").canonicalForm())
        .isEqualTo(
            "transfer-idempotency-v1\n"
                + "operation=TRANSFER\n"
                + "customer=0b0e5f3a-1c2d-4e5f-8a9b-0c1d2e3f4a5b\n"
                + "receiverWalletNumber=11:01234567895\n"
                + "amount=10.50\n"
                + "currency=THB\n"
                + "description=4:rent\n");
  }

  @Test
  void withoutADescriptionTheLineIsNull() {
    TransferRequestFingerprint none =
        TransferRequestFingerprint.builder(
                SENDER, RECEIVER, Money.of(new BigDecimal("10"), Money.THB))
            .build();

    assertThat(none.canonicalForm()).endsWith("description=null\n");
    assertThat(none.sha256Hex()).isEqualTo(transfer(RECEIVER, "10", null).sha256Hex());
    assertThat(transfer(RECEIVER, "10", "").canonicalForm()).endsWith("description=0:\n");
    assertThat(transfer(RECEIVER, "10", "").sha256Hex()).isNotEqualTo(none.sha256Hex());
    assertThat(transfer(RECEIVER, "10", "null").sha256Hex()).isNotEqualTo(none.sha256Hex());
  }

  @Test
  void hashIsSha256OfTheCanonicalFormInLowerCaseHex() throws Exception {
    TransferRequestFingerprint fingerprint = transfer(RECEIVER, "10.00", "rent");
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.canonicalForm().getBytes(StandardCharsets.UTF_8));

    assertThat(fingerprint.sha256Hex()).matches("[0-9a-f]{64}");
    assertThat(fingerprint.sha256Hex()).isEqualTo(HexFormat.of().formatHex(digest));
  }

  @ParameterizedTest
  @ValueSource(strings = {"10", "10.0", "10.00", "10.000", "1E+1"})
  void trailingZerosAndFormatGiveTheSameHash(String amount) {
    assertThat(transfer(RECEIVER, amount, "rent").sha256Hex())
        .isEqualTo(transfer(RECEIVER, "10.00", "rent").sha256Hex());
  }

  @Test
  void aThirdDecimalIsRefusedUpstreamNotRounded() {
    assertThatThrownBy(() -> transfer(RECEIVER, "10.001", "rent"))
        .isInstanceOf(InvalidAmountException.class);
  }

  @Test
  void everyFieldChangesTheHash() {
    String base = transfer(RECEIVER, "10.00", "rent").sha256Hex();

    assertThat(transfer(RECEIVER, "10.01", "rent").sha256Hex()).isNotEqualTo(base);
    assertThat(transfer("01234567896", "10.00", "rent").sha256Hex()).isNotEqualTo(base);
    assertThat(transfer(RECEIVER, "10.00", "Rent").sha256Hex()).isNotEqualTo(base);
    assertThat(transfer(RECEIVER, "10.00", "rent ").sha256Hex()).isNotEqualTo(base);
    assertThat(
            TransferRequestFingerprint.builder(
                    UUID.randomUUID(), RECEIVER, Money.of(new BigDecimal("10"), Money.THB))
                .description("rent")
                .build()
                .sha256Hex())
        .isNotEqualTo(base);
  }

  @Test
  void aDescriptionCannotSpoofTheWalletNumber() {
    // Length prefixes keep the two text fields apart.
    assertThat(transfer("0123", "10", "x").sha256Hex())
        .isNotEqualTo(transfer("0123\namount=10.00", "10", "x").sha256Hex());
  }

  @Test
  void exposesTheSenderAndTheTransferOperation() {
    TransferRequestFingerprint fingerprint = transfer(RECEIVER, "10", null);

    assertThat(fingerprint.customerId()).isEqualTo(SENDER);
    assertThat(fingerprint.operation()).isEqualTo("TRANSFER");
    assertThat(TransferRequestFingerprint.OPERATION).isEqualTo("TRANSFER");
  }

  @Test
  void printsNoRequestValue() {
    String text = transfer(RECEIVER, "10", "rent for May").toString();

    assertThat(text).doesNotContain(RECEIVER, "rent", SENDER.toString(), "10.00");
  }

  @Test
  void refusesADescriptionSetTwice() {
    TransferRequestFingerprint.Builder builder =
        TransferRequestFingerprint.builder(
                SENDER, RECEIVER, Money.of(new BigDecimal("10"), Money.THB))
            .description(null);

    assertThatThrownBy(() -> builder.description("rent"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresSenderRecipientAndAmount() {
    Money amount = Money.of(new BigDecimal("10"), Money.THB);
    assertThatThrownBy(() -> TransferRequestFingerprint.builder(null, RECEIVER, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> TransferRequestFingerprint.builder(SENDER, null, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> TransferRequestFingerprint.builder(SENDER, RECEIVER, null))
        .isInstanceOf(NullPointerException.class);
  }
}
