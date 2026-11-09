package com.portfolio.wallet.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.risk.domain.MvpRiskPolicy;
import com.portfolio.wallet.risk.domain.RiskFacts;
import com.portfolio.wallet.risk.domain.RiskPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.risk} (documentations/06 MVP-ADR-24; doc 07 D27): the shipped {@code application.yaml}
 * values score exactly like the owner's table ({@link MvpRiskPolicy}), and every malformed value
 * stops the start with a message that names the property, never a value.
 */
class RiskPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(RiskProperties.class)
  static class Binding {}

  /** The shipped values, as properties; each test overrides one. */
  static final String[] VALID = {
    "app.risk.amount.bands[0].min-amount=10000.01",
    "app.risk.amount.bands[0].weight=10",
    "app.risk.amount.bands[1].min-amount=25000.01",
    "app.risk.amount.bands[1].weight=20",
    "app.risk.amount.bands[2].min-amount=40000.01",
    "app.risk.amount.bands[2].weight=30",
    "app.risk.frequency.window=10m",
    "app.risk.frequency.bands[0].min-count=3",
    "app.risk.frequency.bands[0].weight=15",
    "app.risk.frequency.bands[1].min-count=5",
    "app.risk.frequency.bands[1].weight=25",
    "app.risk.frequency.bands[2].min-count=8",
    "app.risk.frequency.bands[2].weight=35",
    "app.risk.new-recipient.weight=15",
    "app.risk.new-sender-wallet.bands[0].younger-than=24h",
    "app.risk.new-sender-wallet.bands[0].weight=20",
    "app.risk.new-sender-wallet.bands[1].younger-than=7d",
    "app.risk.new-sender-wallet.bands[1].weight=10",
    "app.risk.thresholds.monitor=40",
    "app.risk.thresholds.manual-review=70",
    "app.risk.thresholds.block=90"
  };

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner().withUserConfiguration(Binding.class);
  }

  /** Probes across every edge of every rule and band. */
  private static List<RiskFacts> probes() {
    List<RiskFacts> probes = new ArrayList<>();
    String[] amounts = {"10000.00", "10000.01", "25000.00", "25000.01", "40000.00", "40000.01"};
    int[] counts = {1, 2, 3, 4, 5, 7, 8};
    Duration[] ages = {
      Duration.ZERO,
      Duration.ofHours(24).minusNanos(1000),
      Duration.ofHours(24),
      Duration.ofDays(7).minusNanos(1000),
      Duration.ofDays(7)
    };
    for (String amount : amounts) {
      for (int count : counts) {
        for (Duration age : ages) {
          for (boolean known : new boolean[] {true, false}) {
            probes.add(new RiskFacts(Money.of(amount, Money.THB), count, known, age));
          }
        }
      }
    }
    return probes;
  }

  private static void assertScoresLikeTheOwnersTable(RiskPolicy policy) {
    RiskPolicy expected = MvpRiskPolicy.create();
    for (RiskFacts facts : probes()) {
      assertThat(policy.evaluate(facts))
          .usingRecursiveComparison()
          .isEqualTo(expected.evaluate(facts));
    }
    for (int score = 0; score <= 100; score++) {
      assertThat(policy.decide(score)).isEqualTo(expected.decide(score));
    }
  }

  @Test
  void theShippedConfigurationScoresLikeTheOwnersTable() {
    runner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              RiskProperties properties = context.getBean(RiskProperties.class);
              assertThat(properties.frequencyWindowSeconds()).isEqualTo(600);
              assertScoresLikeTheOwnersTable(properties.toPolicy());
            });
  }

  @Test
  void theListedValuesBindAndScoreLikeTheOwnersTable() {
    runner()
        .withPropertyValues(VALID)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertScoresLikeTheOwnersTable(context.getBean(RiskProperties.class).toPolicy());
            });
  }

  @Test
  void theConfigurationBuildsThePolicyBean() {
    runner()
        .withUserConfiguration(RiskConfiguration.class)
        .withPropertyValues(VALID)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertScoresLikeTheOwnersTable(context.getBean(RiskPolicy.class));
            });
  }

  @Test
  void missingConfigurationStopsTheStart() {
    runner().run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        // weights outside 1-100
        "app.risk.amount.bands[0].weight=0",
        "app.risk.frequency.bands[2].weight=101",
        "app.risk.new-recipient.weight=0",
        "app.risk.new-recipient.weight=101",
        "app.risk.new-sender-wallet.bands[0].weight=0",
        // amounts: decimals, sign, order
        "app.risk.amount.bands[0].min-amount=10000.001",
        "app.risk.amount.bands[0].min-amount=0",
        "app.risk.amount.bands[0].min-amount=-1",
        "app.risk.amount.bands[1].min-amount=10000.01",
        "app.risk.amount.bands[1].weight=10",
        "app.risk.amount.bands[1].min-amount=",
        // frequency: window, order
        "app.risk.frequency.window=0s",
        "app.risk.frequency.window=1500ms",
        "app.risk.frequency.window=-10m",
        "app.risk.frequency.bands[1].min-count=3",
        "app.risk.frequency.bands[0].min-count=0",
        "app.risk.frequency.bands[1].weight=35",
        // wallet age: order and direction of the weights
        "app.risk.new-sender-wallet.bands[1].younger-than=24h",
        "app.risk.new-sender-wallet.bands[1].weight=20",
        "app.risk.new-sender-wallet.bands[1].weight=30",
        "app.risk.new-sender-wallet.bands[0].younger-than=0s",
        // thresholds
        "app.risk.thresholds.monitor=0",
        "app.risk.thresholds.monitor=70",
        "app.risk.thresholds.manual-review=90",
        "app.risk.thresholds.block=101",
        "app.risk.thresholds.block=69"
      })
  void aMalformedValueStopsTheStart(String override) {
    runner()
        .withPropertyValues(VALID)
        .withPropertyValues(override)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("app.risk");
            });
  }

  @Test
  void theMessageNamesThePropertyNotTheValue() {
    runner()
        .withPropertyValues(VALID)
        .withPropertyValues("app.risk.amount.bands[0].min-amount=12345.678")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("min-amount")
                    .hasMessageNotContaining("12345"));
  }

  @Test
  void aMissingSectionStopsTheStart() {
    List<String> withoutThresholds =
        List.of(VALID).stream().filter(p -> !p.startsWith("app.risk.thresholds")).toList();
    runner()
        .withPropertyValues(withoutThresholds.toArray(String[]::new))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("app.risk.thresholds");
            });
  }
}
