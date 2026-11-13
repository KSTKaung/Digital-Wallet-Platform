package com.portfolio.wallet.notification.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The template values of a money email (documentations/06 MVP-ADR-24 amendment "Emails (Q9)";
 * MVP-ADR-25): the amount, the currency and the server reference, and nothing else. Never a
 * balance, a name, a wallet number or the transfer description; the time of the change is the
 * outbox row's own database time. The outbox accepts exactly these three keys for a money kind (its
 * declared key set), so a row of that kind can hold no other value.
 */
public final class MoneyEmailValues {

  public static final String AMOUNT = "amount";
  public static final String CURRENCY = "currency";
  public static final String REFERENCE = "reference";

  /** The keys every money kind declares. */
  static final Set<String> KEYS = Set.of(AMOUNT, CURRENCY, REFERENCE);

  private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

  /** A server reference ({@code DEP-}, {@code WDR-} or {@code TRF-} and hex digits), V3 and V4. */
  private static final Pattern SERVER_REFERENCE = Pattern.compile("[A-Z]{3}-[0-9A-F]{1,36}");

  /** At most 2 decimals (MVP-ADR-21). */
  private static final int SCALE = 2;

  private MoneyEmailValues() {}

  /**
   * @param amount positive, at most 2 decimals; stored with exactly 2, such as {@code 1300.00}
   * @param currency an ISO 4217 code, such as {@code THB}
   * @param reference the server's reference of the movement
   * @throws IllegalArgumentException for a value of the wrong form; the message never quotes it
   */
  public static Map<String, String> of(BigDecimal amount, String currency, String reference) {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(reference, "reference");
    if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > SCALE) {
      throw new IllegalArgumentException("amount must be positive with at most 2 decimals");
    }
    if (!CURRENCY_CODE.matcher(currency).matches()) {
      throw new IllegalArgumentException("currency must be a 3-letter code");
    }
    if (!SERVER_REFERENCE.matcher(reference).matches()) {
      throw new IllegalArgumentException("reference must be a server reference");
    }
    return Map.of(
        AMOUNT, amount.setScale(SCALE).toPlainString(), CURRENCY, currency, REFERENCE, reference);
  }
}
