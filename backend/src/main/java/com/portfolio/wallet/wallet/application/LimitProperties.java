package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.wallet.domain.LimitPolicy;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.OperationLimits;
import com.portfolio.wallet.wallet.domain.Tier;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Amount limits ({@code app.limits}; documentations/06 MVP-ADR-18), THB. The values live in
 * configuration only: no limit is a literal in code. Checked when the application starts, so a bad
 * value stops the start instead of reaching a money check:
 *
 * <ul>
 *   <li>every value is present and positive;
 *   <li>at most 2 decimal places, value-based like every amount (MVP-ADR-21: {@code 1.000} is
 *       {@code 1.00}, {@code 1.001} is refused);
 *   <li>minimum ≤ maximum per transaction ≤ daily limit, for every tier.
 * </ul>
 *
 * <p>The same {@link LimitPolicy} serves the limits endpoint (PR 4a) and the money checks (PR 4b).
 * Limits are read by the wallet's tier at check time, never copied per wallet (MVP-ADR-18).
 *
 * @param transfer outgoing transfers; the daily limit depends on the tier
 */
@ConfigurationProperties(prefix = "app.limits")
public record LimitProperties(Limits deposit, Limits withdrawal, TieredLimits transfer) {

  public LimitProperties {
    require(deposit, "deposit");
    require(withdrawal, "withdrawal");
    require(transfer, "transfer");
  }

  /** Limits of an operation whose daily limit is the same for every tier. */
  public record Limits(BigDecimal minimum, BigDecimal maximumPerTransaction, BigDecimal daily) {

    public Limits {
      Money min = amount(minimum, "minimum");
      Money max = amount(maximumPerTransaction, "maximum-per-transaction");
      Money day = amount(daily, "daily");
      requireOrdered(min, max, day, "daily");
    }

    OperationLimits toOperationLimits() {
      return OperationLimits.sameForEveryTier(
          Money.of(minimum, Money.THB),
          Money.of(maximumPerTransaction, Money.THB),
          Money.of(daily, Money.THB));
    }
  }

  /** Limits of an operation with a daily limit per tier. */
  public record TieredLimits(
      BigDecimal minimum, BigDecimal maximumPerTransaction, TierAmounts daily) {

    public TieredLimits {
      Money min = amount(minimum, "minimum");
      Money max = amount(maximumPerTransaction, "maximum-per-transaction");
      require(daily, "daily");
      requireOrdered(min, max, amount(daily.basic(), "daily.basic"), "daily.basic");
      requireOrdered(min, max, amount(daily.verified(), "daily.verified"), "daily.verified");
    }

    OperationLimits toOperationLimits() {
      Map<Tier, Money> perTier = new EnumMap<>(Tier.class);
      for (Tier tier : Tier.values()) {
        perTier.put(tier, Money.of(daily.of(tier), Money.THB));
      }
      return new OperationLimits(
          Money.of(minimum, Money.THB), Money.of(maximumPerTransaction, Money.THB), perTier);
    }
  }

  /** One amount per {@link Tier}; Premium is not offered (doc 06 §3, D-05). */
  public record TierAmounts(BigDecimal basic, BigDecimal verified) {

    BigDecimal of(Tier tier) {
      return switch (tier) {
        case BASIC -> basic;
        case VERIFIED -> verified;
      };
    }
  }

  /** The policy every limit check and the limits endpoint use. */
  public LimitPolicy toPolicy() {
    Map<Operation, OperationLimits> limits = new EnumMap<>(Operation.class);
    limits.put(Operation.DEPOSIT, deposit.toOperationLimits());
    limits.put(Operation.WITHDRAWAL, withdrawal.toOperationLimits());
    limits.put(Operation.TRANSFER, transfer.toOperationLimits());
    return new LimitPolicy(limits);
  }

  // Messages name the property, never a value.
  private static void require(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException("app.limits: " + name + " is required");
    }
  }

  private static Money amount(BigDecimal value, String name) {
    require(value, name);
    Money money;
    try {
      money = Money.of(value, Money.THB);
    } catch (InvalidAmountException e) {
      throw new IllegalArgumentException(
          "app.limits: " + name + " must have at most 2 decimal places");
    }
    if (!money.isPositive()) {
      throw new IllegalArgumentException("app.limits: " + name + " must be positive");
    }
    return money;
  }

  private static void requireOrdered(Money min, Money max, Money daily, String dailyName) {
    if (min.isGreaterThan(max)) {
      throw new IllegalArgumentException(
          "app.limits: minimum must not exceed maximum-per-transaction");
    }
    if (max.isGreaterThan(daily)) {
      throw new IllegalArgumentException(
          "app.limits: maximum-per-transaction must not exceed " + dailyName);
    }
  }
}
