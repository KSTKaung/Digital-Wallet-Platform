package com.portfolio.wallet.risk.domain;

import static com.portfolio.wallet.risk.domain.MvpRiskPolicy.thb;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.risk.domain.RiskPolicy.AgeBand;
import com.portfolio.wallet.risk.domain.RiskPolicy.AmountBand;
import com.portfolio.wallet.risk.domain.RiskPolicy.CountBand;
import com.portfolio.wallet.risk.domain.RiskPolicy.Thresholds;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The policy's configuration checks (doc 07 D27 values are configuration; checked at startup):
 * weights 1–100, boundaries strictly ascending, weights moving with the risk, thresholds {@code 1 ≤
 * monitor < manualReview < block ≤ 100}.
 */
class RiskPolicyConfigurationTest {

  private static final List<AmountBand> AMOUNTS = List.of(new AmountBand(thb("10000.01"), 10));
  private static final List<CountBand> COUNTS = List.of(new CountBand(3, 15));
  private static final List<AgeBand> AGES = List.of(new AgeBand(Duration.ofDays(1), 20));
  private static final Thresholds THRESHOLDS = new Thresholds(40, 70, 90);

  @Test
  void aWellFormedPolicyIsAccepted() {
    assertThatCode(() -> new RiskPolicy(AMOUNTS, COUNTS, 15, AGES, THRESHOLDS))
        .doesNotThrowAnyException();
    assertThatCode(MvpRiskPolicy::create).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "weight {0}")
  @ValueSource(ints = {0, -1, 101})
  void weightsOutsideOneToHundredAreRefused(int weight) {
    assertThatThrownBy(() -> new AmountBand(thb("1.00"), weight))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CountBand(1, weight)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgeBand(Duration.ofDays(1), weight))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskPolicy(AMOUNTS, COUNTS, weight, AGES, THRESHOLDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("new recipient");
  }

  @Test
  void weightsOneAndHundredAreAccepted() {
    assertThatCode(() -> new RiskPolicy(AMOUNTS, COUNTS, 1, AGES, THRESHOLDS))
        .doesNotThrowAnyException();
    assertThatCode(() -> new RiskPolicy(AMOUNTS, COUNTS, 100, AGES, THRESHOLDS))
        .doesNotThrowAnyException();
  }

  @Test
  void bandBoundariesMustBeValid() {
    assertThatThrownBy(() -> new AmountBand(thb("0.00"), 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AmountBand(null, 10)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CountBand(0, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgeBand(Duration.ZERO, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgeBand(Duration.ofHours(-1), 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgeBand(null, 10)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void emptyOrMissingListsAreRefused() {
    assertThatThrownBy(() -> new RiskPolicy(List.of(), COUNTS, 15, AGES, THRESHOLDS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskPolicy(AMOUNTS, List.of(), 15, AGES, THRESHOLDS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskPolicy(AMOUNTS, COUNTS, 15, List.of(), THRESHOLDS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskPolicy(null, COUNTS, 15, AGES, THRESHOLDS))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RiskPolicy(AMOUNTS, COUNTS, 15, AGES, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new RiskPolicy(Arrays.asList((AmountBand) null), COUNTS, 15, AGES, THRESHOLDS))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void amountBandsMustAscendInAmountAndWeight() {
    assertThatThrownBy(
            () -> amounts(new AmountBand(thb("25000.01"), 10), new AmountBand(thb("10000.01"), 20)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> amounts(new AmountBand(thb("10000.01"), 10), new AmountBand(thb("10000.01"), 20)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> amounts(new AmountBand(thb("10000.01"), 20), new AmountBand(thb("25000.01"), 20)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> amounts(new AmountBand(thb("10000.01"), 20), new AmountBand(thb("25000.01"), 10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void frequencyBandsMustAscendInCountAndWeight() {
    assertThatThrownBy(() -> counts(new CountBand(5, 15), new CountBand(3, 25)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> counts(new CountBand(3, 15), new CountBand(3, 25)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> counts(new CountBand(3, 25), new CountBand(5, 25)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ageBandsMustAscendInAgeAndDescendInWeight() {
    assertThatThrownBy(
            () -> ages(new AgeBand(Duration.ofDays(7), 10), new AgeBand(Duration.ofDays(1), 20)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ages(new AgeBand(Duration.ofDays(1), 20), new AgeBand(Duration.ofDays(1), 10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ages(new AgeBand(Duration.ofDays(1), 10), new AgeBand(Duration.ofDays(7), 20)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ages(new AgeBand(Duration.ofDays(1), 10), new AgeBand(Duration.ofDays(7), 10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0}, {1}, {2}")
  @CsvSource({
    "0, 70, 90",
    "40, 40, 90",
    "70, 40, 90",
    "40, 90, 90",
    "40, 95, 90",
    "40, 70, 101",
    "-1, 70, 90"
  })
  void thresholdsMustAscendWithinTheScoreRange(int monitor, int manualReview, int block) {
    assertThatThrownBy(() -> new Thresholds(monitor, manualReview, block))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("monitor");
  }

  @Test
  void theTightestThresholdsAreAccepted() {
    assertThatCode(() -> new Thresholds(1, 2, 3)).doesNotThrowAnyException();
    assertThatCode(() -> new Thresholds(98, 99, 100)).doesNotThrowAnyException();
  }

  private static RiskPolicy amounts(AmountBand... bands) {
    return new RiskPolicy(List.of(bands), COUNTS, 15, AGES, THRESHOLDS);
  }

  private static RiskPolicy counts(CountBand... bands) {
    return new RiskPolicy(AMOUNTS, List.of(bands), 15, AGES, THRESHOLDS);
  }

  private static RiskPolicy ages(AgeBand... bands) {
    return new RiskPolicy(AMOUNTS, COUNTS, 15, List.of(bands), THRESHOLDS);
  }
}
