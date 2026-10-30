package com.portfolio.wallet.wallet.domain;

import com.portfolio.wallet.common.money.CurrencyMismatchException;
import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Amount limits per operation and tier (documentations/06 MVP-ADR-18). All values are passed in
 * from configuration; the policy holds no literal limit.
 *
 * <p>Checks, in order:
 *
 * <ol>
 *   <li>amount below the minimum → {@link LimitCheckResult#BELOW_MINIMUM} ({@code INVALID_AMOUNT});
 *   <li>amount above the per-transaction maximum → {@link LimitCheckResult#SINGLE_LIMIT_EXCEEDED};
 *   <li>the day's used + reserved + amount above the daily limit → {@link
 *       LimitCheckResult#DAILY_LIMIT_EXCEEDED}.
 * </ol>
 *
 * Every boundary is inclusive: an amount exactly at the minimum, the maximum or the remaining daily
 * capacity is allowed. The caller supplies the usage of the right business day (MVP-ADR-22), read
 * under the wallet lock (MVP-ADR-08).
 */
public final class LimitPolicy {

  private final Map<Operation, OperationLimits> limits;

  /**
   * @param limits limits for every {@link Operation}
   */
  public LimitPolicy(Map<Operation, OperationLimits> limits) {
    Objects.requireNonNull(limits, "limits");
    Map<Operation, OperationLimits> copy = new EnumMap<>(limits);
    for (Operation operation : Operation.values()) {
      if (copy.get(operation) == null) {
        throw new IllegalArgumentException("limits missing for operation " + operation);
      }
    }
    this.limits = Map.copyOf(copy);
  }

  public OperationLimits limitsFor(Operation operation) {
    return limits.get(Objects.requireNonNull(operation, "operation"));
  }

  /**
   * Checks an amount against the limits of an operation for a tier.
   *
   * @param usedToday completed movements counted on the business day (≥ 0)
   * @param reservedToday amounts held by transfers under review on that day (≥ 0; MVP-ADR-24)
   */
  public LimitCheckResult check(
      Operation operation, Tier tier, Money amount, Money usedToday, Money reservedToday) {
    OperationLimits operationLimits = limitsFor(operation);
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(usedToday, "usedToday");
    Objects.requireNonNull(reservedToday, "reservedToday");
    Money minimum = operationLimits.minimum();
    requireSameCurrency(minimum, amount);
    requireSameCurrency(minimum, usedToday);
    requireSameCurrency(minimum, reservedToday);
    if (usedToday.isNegative() || reservedToday.isNegative()) {
      throw new InvalidAmountException("daily usage must not be negative");
    }

    if (amount.isLessThan(minimum)) {
      return LimitCheckResult.BELOW_MINIMUM;
    }
    if (amount.isGreaterThan(operationLimits.maximumPerTransaction())) {
      return LimitCheckResult.SINGLE_LIMIT_EXCEEDED;
    }
    // Plain BigDecimal sum: the total is only compared, so it may exceed the Money range.
    BigDecimal total = amount.amount().add(usedToday.amount()).add(reservedToday.amount());
    if (total.compareTo(operationLimits.dailyLimit(tier).amount()) > 0) {
      return LimitCheckResult.DAILY_LIMIT_EXCEEDED;
    }
    return LimitCheckResult.WITHIN_LIMITS;
  }

  /**
   * Like {@link #check}, but throws for the first rule that fails.
   *
   * @throws InvalidAmountException below the minimum
   * @throws SingleLimitExceededException above the per-transaction maximum
   * @throws DailyLimitExceededException over the daily limit
   */
  public void enforce(
      Operation operation, Tier tier, Money amount, Money usedToday, Money reservedToday) {
    switch (check(operation, tier, amount, usedToday, reservedToday)) {
      case WITHIN_LIMITS -> {}
      case BELOW_MINIMUM -> throw new InvalidAmountException("amount is below the minimum");
      case SINGLE_LIMIT_EXCEEDED ->
          throw new SingleLimitExceededException("amount exceeds the per-transaction limit");
      case DAILY_LIMIT_EXCEEDED ->
          throw new DailyLimitExceededException("amount exceeds the daily limit");
    }
  }

  private static void requireSameCurrency(Money expected, Money actual) {
    if (!expected.currency().equals(actual.currency())) {
      throw new CurrencyMismatchException("amount currency differs from the limit currency");
    }
  }
}
