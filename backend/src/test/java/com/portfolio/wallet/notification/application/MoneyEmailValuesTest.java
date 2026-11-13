package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The values of a money email: amount, currency and server reference only (MVP-ADR-24, Q9). */
class MoneyEmailValuesTest {

  private static final String REFERENCE = "TRF-0123456789ABCDEF0123456789ABCDEF";

  @Test
  void theAmountIsStoredWithTwoDecimalsBesideTheCurrencyAndTheReference() {
    assertThat(MoneyEmailValues.of(new BigDecimal("1300.5"), "THB", REFERENCE))
        .isEqualTo(Map.of("amount", "1300.50", "currency", "THB", "reference", REFERENCE));
    assertThat(MoneyEmailValues.of(new BigDecimal("1E+3"), "THB", "DEP-AB12"))
        .containsEntry("amount", "1000.00");
    assertThat(MoneyEmailValues.of(new BigDecimal("0.010"), "THB", "WDR-AB12"))
        .containsEntry("amount", "0.01");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1.00", "1.001"})
  void anAmountThatIsNotPositiveOrHasMoreThanTwoDecimalsIsRefused(String amount) {
    assertThatThrownBy(() -> MoneyEmailValues.of(new BigDecimal(amount), "THB", REFERENCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(amount);
  }

  /** Only a server reference: never free text such as a deposit note or a description. */
  @ParameterizedTest
  @ValueSource(strings = {"", "salary", "TRF-xyz", "TRF-0123 4567", "trf-0123", "TRF-"})
  void anythingButAServerReferenceIsRefusedWithoutQuotingIt(String reference) {
    assertThatThrownBy(() -> MoneyEmailValues.of(BigDecimal.TEN, "THB", reference))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reference must be a server reference");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "thb", "BAHT", "TH"})
  void anythingButACurrencyCodeIsRefused(String currency) {
    assertThatThrownBy(() -> MoneyEmailValues.of(BigDecimal.TEN, currency, REFERENCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("currency must be a 3-letter code");
  }

  @Test
  void everyValueIsRequired() {
    assertThatThrownBy(() -> MoneyEmailValues.of(null, "THB", REFERENCE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> MoneyEmailValues.of(BigDecimal.TEN, null, REFERENCE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> MoneyEmailValues.of(BigDecimal.TEN, "THB", null))
        .isInstanceOf(NullPointerException.class);
  }
}
