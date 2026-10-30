package com.portfolio.wallet.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An immutable amount of money in an explicit ISO 4217 currency (documentations/06 MVP-ADR-21).
 *
 * <ul>
 *   <li>The amount is a {@link BigDecimal} with scale exactly {@value #SCALE}, matching the
 *       database type {@code NUMERIC(19,2)}: at most {@value #MAX_INTEGER_DIGITS} integer digits.
 *   <li>An input with more than 2 significant decimal places is rejected, never rounded.
 *   <li>THB is the only supported currency.
 *   <li>Values may be negative or zero: ledger-derived balances of system accounts go negative (doc
 *       01 §6.2). Whether an amount must be positive is a rule of the caller.
 * </ul>
 *
 * <p>Because the scale is always 2, {@link #equals} agrees with {@link #compareTo}.
 */
public final class Money implements Comparable<Money> {

  /** Decimal places of every amount: THB has two minor units (MVP-ADR-21). */
  public static final int SCALE = 2;

  /** {@code NUMERIC(19,2)}: 19 digits of precision, 2 of them after the decimal point. */
  public static final int MAX_INTEGER_DIGITS = 17;

  /** The only supported currency in the MVP (MVP-ADR-21; doc 05 §4). */
  public static final Currency THB = Currency.getInstance("THB");

  private final BigDecimal amount;
  private final Currency currency;

  private Money(BigDecimal amount, Currency currency) {
    this.amount = amount;
    this.currency = currency;
  }

  /**
   * Creates an amount without rounding.
   *
   * @throws InvalidAmountException if the amount has more than 2 decimal places or more than 17
   *     integer digits
   * @throws CurrencyMismatchException if the currency is not supported
   */
  public static Money of(BigDecimal amount, Currency currency) {
    Objects.requireNonNull(amount, "amount");
    requireSupportedCurrency(currency);
    // Strip first, then check the magnitude and the decimals before any rescaling: a value such as
    // 1E-999999999 or 1E+999999999 would otherwise make setScale build an enormous power of ten.
    BigDecimal stripped = amount.stripTrailingZeros();
    if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
      throw new InvalidAmountException("amount exceeds the maximum number of integer digits");
    }
    if (stripped.scale() > SCALE) {
      throw new InvalidAmountException("amount has more than 2 decimal places");
    }
    // UNNECESSARY: the checks above guarantee no rounding; this throws instead of rounding.
    return new Money(stripped.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
  }

  /**
   * Parses a plain decimal string such as {@code "100.00"}.
   *
   * @throws InvalidAmountException if the text is not a number or the amount is not acceptable
   */
  public static Money of(String amount, Currency currency) {
    Objects.requireNonNull(amount, "amount");
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(amount);
    } catch (NumberFormatException e) {
      throw new InvalidAmountException("amount is not a number");
    }
    return of(parsed, currency);
  }

  public static Money zero(Currency currency) {
    return of(BigDecimal.ZERO, currency);
  }

  /**
   * @throws CurrencyMismatchException if the currency is not THB
   */
  public static void requireSupportedCurrency(Currency currency) {
    Objects.requireNonNull(currency, "currency");
    if (!THB.equals(currency)) {
      throw new CurrencyMismatchException("currency is not supported");
    }
  }

  public BigDecimal amount() {
    return amount;
  }

  public Currency currency() {
    return currency;
  }

  /**
   * @throws InvalidAmountException if the result exceeds 17 integer digits
   */
  public Money plus(Money other) {
    requireSameCurrency(other);
    return of(amount.add(other.amount), currency);
  }

  /**
   * The result may be negative.
   *
   * @throws InvalidAmountException if the result exceeds 17 integer digits
   */
  public Money minus(Money other) {
    requireSameCurrency(other);
    return of(amount.subtract(other.amount), currency);
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  public boolean isGreaterThan(Money other) {
    return compareTo(other) > 0;
  }

  public boolean isGreaterThanOrEqualTo(Money other) {
    return compareTo(other) >= 0;
  }

  public boolean isLessThan(Money other) {
    return compareTo(other) < 0;
  }

  /**
   * @throws CurrencyMismatchException if the currencies differ
   */
  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      throw new CurrencyMismatchException("amounts are in different currencies");
    }
  }

  @Override
  public boolean equals(Object o) {
    // Scale is always 2, so BigDecimal.equals gives the same answer as compareTo.
    return o instanceof Money other
        && amount.equals(other.amount)
        && currency.equals(other.currency);
  }

  @Override
  public int hashCode() {
    return Objects.hash(amount, currency);
  }

  @Override
  public String toString() {
    return amount.toPlainString() + " " + currency.getCurrencyCode();
  }
}
