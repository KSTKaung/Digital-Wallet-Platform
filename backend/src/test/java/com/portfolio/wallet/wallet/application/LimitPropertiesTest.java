package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.wallet.application.LimitProperties.Limits;
import com.portfolio.wallet.wallet.application.LimitProperties.TierAmounts;
import com.portfolio.wallet.wallet.application.LimitProperties.TieredLimits;
import com.portfolio.wallet.wallet.domain.LimitPolicy;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.OperationLimits;
import com.portfolio.wallet.wallet.domain.Tier;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.limits} (documentations/06 MVP-ADR-18): the shipped values, bound into the PR 2 {@link
 * LimitPolicy}, and the startup checks (minimum ≤ maximum ≤ daily, at most 2 decimals).
 */
class LimitPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(LimitProperties.class)
  static class Binding {}

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static Money thb(String value) {
    return Money.of(value, Money.THB);
  }

  static LimitProperties adrValues() {
    return new LimitProperties(
        new Limits(d("100.00"), d("100000.00"), d("200000.00")),
        new Limits(d("100.00"), d("30000.00"), d("50000.00")),
        new TieredLimits(
            d("1.00"), d("50000.00"), new TierAmounts(d("100000.00"), d("500000.00"))));
  }

  @Test
  void theMvpAdr18ValuesBecomeThePolicy() {
    LimitPolicy policy = adrValues().toPolicy();

    OperationLimits deposit = policy.limitsFor(Operation.DEPOSIT);
    assertThat(deposit.minimum()).isEqualTo(thb("100.00"));
    assertThat(deposit.maximumPerTransaction()).isEqualTo(thb("100000.00"));
    assertThat(deposit.dailyLimit(Tier.BASIC)).isEqualTo(thb("200000.00"));
    assertThat(deposit.dailyLimit(Tier.VERIFIED)).isEqualTo(thb("200000.00"));

    OperationLimits withdrawal = policy.limitsFor(Operation.WITHDRAWAL);
    assertThat(withdrawal.minimum()).isEqualTo(thb("100.00"));
    assertThat(withdrawal.maximumPerTransaction()).isEqualTo(thb("30000.00"));
    assertThat(withdrawal.dailyLimit(Tier.VERIFIED)).isEqualTo(thb("50000.00"));

    OperationLimits transfer = policy.limitsFor(Operation.TRANSFER);
    assertThat(transfer.minimum()).isEqualTo(thb("1.00"));
    assertThat(transfer.maximumPerTransaction()).isEqualTo(thb("50000.00"));
    assertThat(transfer.dailyLimit(Tier.BASIC)).isEqualTo(thb("100000.00"));
    assertThat(transfer.dailyLimit(Tier.VERIFIED)).isEqualTo(thb("500000.00"));
  }

  @Test
  void trailingZerosAreAcceptedAndMinimumMayEqualMaximumAndDaily() {
    Limits limits = new Limits(d("5.000"), d("5"), d("5.00"));

    assertThat(limits.toOperationLimits().minimum().amount()).isEqualByComparingTo("5.00");
    assertThat(limits.toOperationLimits().minimum().amount().scale()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"100.001", "0.005"})
  void aThirdDecimalIsRefused(String value) {
    assertThatThrownBy(() -> new Limits(d(value), d("1000.00"), d("2000.00")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("app.limits: minimum must have at most 2 decimal places");
  }

  @Test
  void eachValueMustBePresentAndPositive() {
    assertThatThrownBy(() -> new Limits(null, d("1"), d("2")))
        .hasMessage("app.limits: minimum is required");
    assertThatThrownBy(() -> new Limits(d("0"), d("1"), d("2")))
        .hasMessage("app.limits: minimum must be positive");
    assertThatThrownBy(() -> new Limits(d("1"), d("-1"), d("2")))
        .hasMessage("app.limits: maximum-per-transaction must be positive");
    assertThatThrownBy(() -> new TieredLimits(d("1"), d("2"), null))
        .hasMessage("app.limits: daily is required");
    assertThatThrownBy(() -> new TieredLimits(d("1"), d("2"), new TierAmounts(d("3"), null)))
        .hasMessage("app.limits: daily.verified is required");
    assertThatThrownBy(
            () -> new LimitProperties(null, adrValues().withdrawal(), adrValues().transfer()))
        .hasMessage("app.limits: deposit is required");
  }

  @Test
  void minimumMaximumAndDailyMustBeOrdered() {
    assertThatThrownBy(() -> new Limits(d("101.00"), d("100.00"), d("200.00")))
        .hasMessage("app.limits: minimum must not exceed maximum-per-transaction");
    assertThatThrownBy(() -> new Limits(d("1.00"), d("200.01"), d("200.00")))
        .hasMessage("app.limits: maximum-per-transaction must not exceed daily");
    assertThatThrownBy(
            () ->
                new TieredLimits(
                    d("1.00"), d("50000.00"), new TierAmounts(d("49999.99"), d("500000.00"))))
        .hasMessage("app.limits: maximum-per-transaction must not exceed daily.basic");
  }

  // Binding from configuration --------------------------------------------------------------------

  private static final String[] ADR_PROPERTIES = {
    "app.limits.deposit.minimum=100.00",
    "app.limits.deposit.maximum-per-transaction=100000.00",
    "app.limits.deposit.daily=200000.00",
    "app.limits.withdrawal.minimum=100.00",
    "app.limits.withdrawal.maximum-per-transaction=30000.00",
    "app.limits.withdrawal.daily=50000.00",
    "app.limits.transfer.minimum=1.00",
    "app.limits.transfer.maximum-per-transaction=50000.00",
    "app.limits.transfer.daily.basic=100000.00",
    "app.limits.transfer.daily.verified=500000.00"
  };

  @Test
  void bindsFromProperties() {
    new ApplicationContextRunner()
        .withUserConfiguration(Binding.class)
        .withPropertyValues(ADR_PROPERTIES)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(LimitProperties.class)).isEqualTo(adrValues());
            });
  }

  @Test
  void aBadValueStopsTheStart() {
    new ApplicationContextRunner()
        .withUserConfiguration(Binding.class)
        .withPropertyValues(ADR_PROPERTIES)
        .withPropertyValues("app.limits.withdrawal.maximum-per-transaction=50000.01")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void missingLimitsStopTheStart() {
    new ApplicationContextRunner()
        .withUserConfiguration(Binding.class)
        .run(context -> assertThat(context).hasFailed());
  }
}
