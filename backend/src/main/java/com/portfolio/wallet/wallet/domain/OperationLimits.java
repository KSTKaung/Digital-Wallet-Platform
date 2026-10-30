package com.portfolio.wallet.wallet.domain;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.Money;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Limits of one operation (documentations/06 MVP-ADR-18): minimum, per-transaction maximum and a
 * daily limit for every tier. Values come from configuration, never literals.
 *
 * @param dailyLimits daily limit per tier; must contain every {@link Tier}
 */
public record OperationLimits(
    Money minimum, Money maximumPerTransaction, Map<Tier, Money> dailyLimits) {

  public OperationLimits {
    Objects.requireNonNull(minimum, "minimum");
    Objects.requireNonNull(maximumPerTransaction, "maximumPerTransaction");
    Objects.requireNonNull(dailyLimits, "dailyLimits");
    dailyLimits = Map.copyOf(new EnumMap<>(dailyLimits));
    if (!minimum.isPositive()) {
      throw new IllegalArgumentException("minimum must be positive");
    }
    if (minimum.isGreaterThan(maximumPerTransaction)) {
      throw new IllegalArgumentException("minimum must not exceed the per-transaction maximum");
    }
    for (Tier tier : Tier.values()) {
      Money daily = dailyLimits.get(tier);
      if (daily == null) {
        throw new IllegalArgumentException("daily limit missing for tier " + tier);
      }
      if (!daily.currency().equals(minimum.currency())) {
        throw new CurrencyMismatchException("limits are in different currencies");
      }
      if (!daily.isPositive()) {
        throw new IllegalArgumentException("daily limit must be positive");
      }
    }
  }

  /** The same daily limit for every tier (deposit and withdrawal). */
  public static OperationLimits sameForEveryTier(
      Money minimum, Money maximumPerTransaction, Money dailyLimit) {
    Map<Tier, Money> daily = new EnumMap<>(Tier.class);
    for (Tier tier : Tier.values()) {
      daily.put(tier, dailyLimit);
    }
    return new OperationLimits(minimum, maximumPerTransaction, daily);
  }

  public Money dailyLimit(Tier tier) {
    return dailyLimits.get(Objects.requireNonNull(tier, "tier"));
  }
}
