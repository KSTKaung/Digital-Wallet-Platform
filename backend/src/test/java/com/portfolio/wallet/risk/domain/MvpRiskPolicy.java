package com.portfolio.wallet.risk.domain;

import com.portfolio.wallet.common.money.Money;
import java.time.Duration;
import java.util.List;

/**
 * Test fixture: the policy of the owner's values (documentations/06 MVP-ADR-24 "Score" and "Bands";
 * doc 07 D27), as {@code application.yaml} configures them. {@code RiskPropertiesTest} proves that
 * the shipped configuration scores like this policy.
 */
public final class MvpRiskPolicy {

  private MvpRiskPolicy() {}

  public static RiskPolicy create() {
    return new RiskPolicy(
        List.of(
            new RiskPolicy.AmountBand(thb("10000.01"), 10),
            new RiskPolicy.AmountBand(thb("25000.01"), 20),
            new RiskPolicy.AmountBand(thb("40000.01"), 30)),
        List.of(
            new RiskPolicy.CountBand(3, 15),
            new RiskPolicy.CountBand(5, 25),
            new RiskPolicy.CountBand(8, 35)),
        15,
        List.of(
            new RiskPolicy.AgeBand(Duration.ofHours(24), 20),
            new RiskPolicy.AgeBand(Duration.ofDays(7), 10)),
        new RiskPolicy.Thresholds(40, 70, 90));
  }

  public static Money thb(String amount) {
    return Money.of(amount, Money.THB);
  }
}
