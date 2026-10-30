package com.portfolio.wallet.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Limit checks with the documented values of documentations/06 MVP-ADR-18, passed in as the
 * configuration would.
 */
class LimitPolicyTest {

  private static final Money ZERO = Money.zero(Money.THB);

  private static Money thb(String amount) {
    return Money.of(amount, Money.THB);
  }

  private static final LimitPolicy POLICY =
      new LimitPolicy(
          Map.of(
              Operation.TRANSFER,
              new OperationLimits(
                  thb("1.00"),
                  thb("50000.00"),
                  Map.of(Tier.BASIC, thb("100000.00"), Tier.VERIFIED, thb("500000.00"))),
              Operation.DEPOSIT,
              OperationLimits.sameForEveryTier(thb("100.00"), thb("100000.00"), thb("200000.00")),
              Operation.WITHDRAWAL,
              OperationLimits.sameForEveryTier(thb("100.00"), thb("30000.00"), thb("50000.00"))));

  @ParameterizedTest(name = "{0} {1} amount={2} used={3} reserved={4} -> {5}")
  @CsvSource({
    // Minimum, at the minimum and 0.01 below.
    "TRANSFER,   BASIC,    1.00,      0,         0,        WITHIN_LIMITS",
    "TRANSFER,   BASIC,    0.99,      0,         0,        BELOW_MINIMUM",
    "DEPOSIT,    BASIC,    100.00,    0,         0,        WITHIN_LIMITS",
    "DEPOSIT,    BASIC,    99.99,     0,         0,        BELOW_MINIMUM",
    "WITHDRAWAL, VERIFIED, 99.99,     0,         0,        BELOW_MINIMUM",
    // Per-transaction maximum, at the maximum and +0.01.
    "TRANSFER,   VERIFIED, 50000.00,  0,         0,        WITHIN_LIMITS",
    "TRANSFER,   VERIFIED, 50000.01,  0,         0,        SINGLE_LIMIT_EXCEEDED",
    "DEPOSIT,    BASIC,    100000.00, 0,         0,        WITHIN_LIMITS",
    "DEPOSIT,    BASIC,    100000.01, 0,         0,        SINGLE_LIMIT_EXCEEDED",
    "WITHDRAWAL, BASIC,    30000.00,  0,         0,        WITHIN_LIMITS",
    "WITHDRAWAL, BASIC,    30000.01,  0,         0,        SINGLE_LIMIT_EXCEEDED",
    // Daily limit, exactly at the limit and +0.01; used and reserved both count.
    "TRANSFER,   BASIC,    50000.00,  30000.00,  20000.00, WITHIN_LIMITS",
    "TRANSFER,   BASIC,    50000.00,  30000.00,  20000.01, DAILY_LIMIT_EXCEEDED",
    "TRANSFER,   BASIC,    0.01,      100000.00, 0,        BELOW_MINIMUM",
    "TRANSFER,   BASIC,    1.00,      99999.00,  0,        WITHIN_LIMITS",
    "TRANSFER,   BASIC,    1.00,      99999.01,  0,        DAILY_LIMIT_EXCEEDED",
    "TRANSFER,   VERIFIED, 1.00,      499999.00, 0,        WITHIN_LIMITS",
    "TRANSFER,   VERIFIED, 1.00,      0,         499999.01, DAILY_LIMIT_EXCEEDED",
    "DEPOSIT,    VERIFIED, 100.00,    199900.00, 0,        WITHIN_LIMITS",
    "DEPOSIT,    VERIFIED, 100.01,    199900.00, 0,        DAILY_LIMIT_EXCEEDED",
    "WITHDRAWAL, BASIC,    20000.00,  30000.00,  0,        WITHIN_LIMITS",
    "WITHDRAWAL, BASIC,    20000.01,  30000.00,  0,        DAILY_LIMIT_EXCEEDED",
    // The per-transaction rule is reported before the daily rule.
    "TRANSFER,   BASIC,    50000.01,  100000.00, 0,        SINGLE_LIMIT_EXCEEDED",
  })
  void reportsTheFirstFailingRule(
      Operation operation,
      Tier tier,
      String amount,
      String used,
      String reserved,
      LimitCheckResult expected) {
    assertThat(POLICY.check(operation, tier, thb(amount), thb(used), thb(reserved)))
        .isEqualTo(expected);
  }

  @Test
  void enforceThrowsTheMatchingException() {
    assertThatCode(() -> POLICY.enforce(Operation.TRANSFER, Tier.BASIC, thb("1.00"), ZERO, ZERO))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> POLICY.enforce(Operation.TRANSFER, Tier.BASIC, thb("0.99"), ZERO, ZERO))
        .isInstanceOf(InvalidAmountException.class);
    assertThatThrownBy(
            () -> POLICY.enforce(Operation.TRANSFER, Tier.BASIC, thb("50000.01"), ZERO, ZERO))
        .isInstanceOf(SingleLimitExceededException.class);
    assertThatThrownBy(
            () ->
                POLICY.enforce(Operation.TRANSFER, Tier.BASIC, thb("1.00"), thb("100000.00"), ZERO))
        .isInstanceOf(DailyLimitExceededException.class);
  }

  @Test
  void zeroAndNegativeAmountsAreBelowTheMinimum() {
    assertThat(POLICY.check(Operation.DEPOSIT, Tier.BASIC, ZERO, ZERO, ZERO))
        .isEqualTo(LimitCheckResult.BELOW_MINIMUM);
    assertThat(POLICY.check(Operation.DEPOSIT, Tier.BASIC, thb("-100.00"), ZERO, ZERO))
        .isEqualTo(LimitCheckResult.BELOW_MINIMUM);
  }

  @Test
  void negativeUsageIsRejected() {
    assertThatThrownBy(
            () -> POLICY.check(Operation.TRANSFER, Tier.BASIC, thb("1.00"), thb("-0.01"), ZERO))
        .isInstanceOf(InvalidAmountException.class);
  }

  @Test
  void everyOperationAndTierNeedsLimits() {
    OperationLimits limits =
        OperationLimits.sameForEveryTier(thb("1.00"), thb("10.00"), thb("20.00"));

    assertThatThrownBy(() -> new LimitPolicy(Map.of(Operation.TRANSFER, limits)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new OperationLimits(thb("1.00"), thb("10.00"), Map.of(Tier.BASIC, thb("20.00"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void limitsMustBeConsistent() {
    assertThatThrownBy(() -> OperationLimits.sameForEveryTier(ZERO, thb("10.00"), thb("20.00")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> OperationLimits.sameForEveryTier(thb("10.01"), thb("10.00"), thb("20.00")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OperationLimits.sameForEveryTier(thb("1.00"), thb("10.00"), ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exposesTheConfiguredLimits() {
    OperationLimits transfer = POLICY.limitsFor(Operation.TRANSFER);

    assertThat(transfer.dailyLimit(Tier.BASIC)).isEqualTo(thb("100000.00"));
    assertThat(transfer.dailyLimit(Tier.VERIFIED)).isEqualTo(thb("500000.00"));
    assertThat(transfer.maximumPerTransaction()).isEqualTo(thb("50000.00"));
  }
}
