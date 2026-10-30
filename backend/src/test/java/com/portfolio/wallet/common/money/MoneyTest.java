package com.portfolio.wallet.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** documentations/08 §4.5 test case 9; doc 06 MVP-ADR-21. */
class MoneyTest {

  private static final Currency THB = Money.THB;

  @ParameterizedTest
  @ValueSource(strings = {"1.00", "0.01", "1", "1.0", "1.0000", "0", "-5.25", "100000.00"})
  void acceptsAtMostTwoSignificantDecimals(String text) {
    Money money = Money.of(new BigDecimal(text), THB);

    assertThat(money.amount().scale()).isEqualTo(2);
    assertThat(money.amount()).isEqualByComparingTo(text);
  }

  @Test
  void keepsTheExactValueWithScaleTwo() {
    assertThat(Money.of(new BigDecimal("1.00"), THB).amount()).isEqualTo(new BigDecimal("1.00"));
    assertThat(Money.of(new BigDecimal("0.01"), THB).amount()).isEqualTo(new BigDecimal("0.01"));
    assertThat(Money.of(new BigDecimal("7"), THB).amount()).isEqualTo(new BigDecimal("7.00"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.001", "0.005", "1.009", "-0.001", "1E-999999999"})
  void rejectsMoreThanTwoDecimalsWithoutRounding(String text) {
    assertThatThrownBy(() -> Money.of(new BigDecimal(text), THB))
        .isInstanceOf(InvalidAmountException.class)
        .hasMessage("amount has more than 2 decimal places")
        .hasMessageNotContaining(text);
  }

  @Test
  void acceptsTheLargestNumeric19Scale2Value() {
    String max = "99999999999999999.99";

    assertThat(Money.of(new BigDecimal(max), THB).amount()).isEqualTo(new BigDecimal(max));
    assertThat(Money.of(new BigDecimal("-" + max), THB).isNegative()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"100000000000000000", "100000000000000000.00", "-1E+17", "1E+999999999"})
  void rejectsOverflowBeyond17IntegerDigits(String text) {
    assertThatThrownBy(() -> Money.of(new BigDecimal(text), THB))
        .isInstanceOf(InvalidAmountException.class)
        .hasMessage("amount exceeds the maximum number of integer digits");
  }

  @Test
  void additionThatOverflowsIsRejected() {
    Money max = Money.of("99999999999999999.99", THB);

    assertThatThrownBy(() -> max.plus(Money.of("0.01", THB)))
        .isInstanceOf(InvalidAmountException.class);
  }

  @Test
  void parsesStringsAndRejectsNonNumbers() {
    assertThat(Money.of("12.30", THB)).isEqualTo(Money.of(new BigDecimal("12.3"), THB));
    assertThatThrownBy(() -> Money.of("12,30", THB))
        .isInstanceOf(InvalidAmountException.class)
        .hasMessage("amount is not a number");
  }

  @Test
  void onlyThbIsSupported() {
    assertThatThrownBy(() -> Money.of(BigDecimal.ONE, Currency.getInstance("USD")))
        .isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void equalsAgreesWithCompareTo() {
    Money a = Money.of(new BigDecimal("1"), THB);
    Money b = Money.of(new BigDecimal("1.00"), THB);
    Money c = Money.of(new BigDecimal("1.0000"), THB);
    Money d = Money.of(new BigDecimal("1.01"), THB);

    assertThat(a.compareTo(b)).isZero();
    assertThat(a).isEqualTo(b).isEqualTo(c).hasSameHashCodeAs(b).hasSameHashCodeAs(c);
    assertThat(a.compareTo(d)).isNegative();
    assertThat(a).isNotEqualTo(d);
  }

  @Test
  void arithmeticAndComparisons() {
    Money ten = Money.of("10.00", THB);
    Money three = Money.of("3.50", THB);

    assertThat(ten.plus(three)).isEqualTo(Money.of("13.50", THB));
    assertThat(ten.minus(three)).isEqualTo(Money.of("6.50", THB));
    assertThat(three.minus(ten)).isEqualTo(Money.of("-6.50", THB));
    assertThat(three.minus(ten).isNegative()).isTrue();
    assertThat(ten.isPositive()).isTrue();
    assertThat(Money.zero(THB).isZero()).isTrue();
    assertThat(Money.zero(THB).isPositive()).isFalse();
    assertThat(Money.zero(THB).isNegative()).isFalse();
    assertThat(ten.isGreaterThan(three)).isTrue();
    assertThat(three.isLessThan(ten)).isTrue();
    assertThat(ten.isGreaterThanOrEqualTo(Money.of("10", THB))).isTrue();
    assertThat(ten.isGreaterThan(Money.of("10", THB))).isFalse();
  }

  @Test
  void toStringShowsPlainAmountAndCode() {
    assertThat(Money.of("1E+3", THB)).hasToString("1000.00 THB");
  }
}
