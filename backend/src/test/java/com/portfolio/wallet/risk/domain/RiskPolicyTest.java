package com.portfolio.wallet.risk.domain;

import static com.portfolio.wallet.risk.domain.MvpRiskPolicy.thb;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.Money;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The risk score (documentations/06 MVP-ADR-24 "Score" and "Bands"; doc 08 §4C.4 criterion 3):
 * every band edge of every rule, the highest weight per rule, the sum, the cap at 100 and the
 * decision bands 39/40, 69/70 and 89/90, with the owner's values ({@link MvpRiskPolicy}).
 */
class RiskPolicyTest {

  private static final Duration OLD_WALLET = Duration.ofDays(30);

  private final RiskPolicy policy = MvpRiskPolicy.create();

  /**
   * Facts that trigger no rule: a small amount, the first transfer, a known recipient, old wallet.
   */
  private static RiskFacts quiet() {
    return new RiskFacts(thb("100.00"), 1, true, OLD_WALLET);
  }

  private static RiskFacts amount(String amount) {
    return new RiskFacts(thb(amount), 1, true, OLD_WALLET);
  }

  private static RiskFacts count(int transfers) {
    return new RiskFacts(thb("100.00"), transfers, true, OLD_WALLET);
  }

  private static RiskFacts age(Duration age) {
    return new RiskFacts(thb("100.00"), 1, true, age);
  }

  private static Map<RiskRule, Integer> hits(RiskEvaluation evaluation) {
    return evaluation.hits().stream().collect(Collectors.toMap(RuleHit::rule, RuleHit::weight));
  }

  private Integer weightOf(RiskRule rule, RiskFacts facts) {
    return hits(policy.evaluate(facts)).get(rule);
  }

  @Test
  void noRuleHitScoresZeroAndAllows() {
    RiskEvaluation evaluation = policy.evaluate(quiet());

    assertThat(evaluation.score()).isZero();
    assertThat(evaluation.decision()).isEqualTo(RiskDecision.ALLOW);
    assertThat(evaluation.hits()).isEmpty();
  }

  // Amount ------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} THB -> {1}")
  @CsvSource(
      nullValues = "none",
      value = {
        "0.01, none",
        "10000.00, none",
        "10000.01, 10",
        "25000.00, 10",
        "25000.01, 20",
        "40000.00, 20",
        "40000.01, 30",
        "50000.00, 30",
        "99999999999999999.99, 30"
      })
  void amountBandEdges(String amount, Integer weight) {
    assertThat(weightOf(RiskRule.TRANSFER_AMOUNT, amount(amount))).isEqualTo(weight);
  }

  // Frequency ---------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} transfers -> {1}")
  @CsvSource(
      nullValues = "none",
      value = {"1, none", "2, none", "3, 15", "4, 15", "5, 25", "7, 25", "8, 35", "1000, 35"})
  void frequencyBandEdges(int transfers, Integer weight) {
    assertThat(weightOf(RiskRule.TRANSFER_FREQUENCY, count(transfers))).isEqualTo(weight);
  }

  // New recipient -----------------------------------------------------------------------------

  @Test
  void aFirstTransferToTheRecipientHits() {
    RiskFacts first = new RiskFacts(thb("100.00"), 1, false, OLD_WALLET);

    assertThat(weightOf(RiskRule.NEW_RECIPIENT, first)).isEqualTo(15);
    assertThat(weightOf(RiskRule.NEW_RECIPIENT, quiet())).isNull();
  }

  // Wallet age --------------------------------------------------------------------------------

  static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);

  @Test
  void walletAgeBandEdges() {
    Duration day = Duration.ofHours(24);
    Duration week = Duration.ofDays(7);

    assertThat(weightOf(RiskRule.NEW_SENDER_WALLET, age(Duration.ZERO))).isEqualTo(20);
    assertThat(weightOf(RiskRule.NEW_SENDER_WALLET, age(day.minus(MICRO)))).isEqualTo(20);
    assertThat(weightOf(RiskRule.NEW_SENDER_WALLET, age(day))).isEqualTo(10);
    assertThat(weightOf(RiskRule.NEW_SENDER_WALLET, age(week.minus(MICRO)))).isEqualTo(10);
    assertThat(weightOf(RiskRule.NEW_SENDER_WALLET, age(week))).isNull();
  }

  // Sum, highest weight per rule, cap ---------------------------------------------------------

  @Test
  void onlyTheHighestMatchingWeightPerRuleApplies() {
    // 50,000 matches all three amount bands, 8 transfers all three frequency bands and a new
    // wallet both age bands: one hit per rule, each at its highest weight.
    RiskEvaluation evaluation =
        policy.evaluate(new RiskFacts(thb("50000.00"), 8, true, Duration.ofHours(1)));

    assertThat(hits(evaluation))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                RiskRule.TRANSFER_AMOUNT, 30,
                RiskRule.TRANSFER_FREQUENCY, 35,
                RiskRule.NEW_SENDER_WALLET, 20));
    assertThat(evaluation.score()).isEqualTo(85);
  }

  @Test
  void everyRuleAtItsHighestSumsToOneHundred() {
    RiskEvaluation evaluation =
        policy.evaluate(new RiskFacts(thb("40000.01"), 8, false, Duration.ZERO));

    assertThat(evaluation.hits())
        .extracting(RuleHit::rule)
        .containsExactly(
            RiskRule.TRANSFER_AMOUNT,
            RiskRule.TRANSFER_FREQUENCY,
            RiskRule.NEW_RECIPIENT,
            RiskRule.NEW_SENDER_WALLET);
    assertThat(evaluation.score()).isEqualTo(100);
    assertThat(evaluation.decision()).isEqualTo(RiskDecision.BLOCK);
  }

  @Test
  void theScoreIsCappedAtOneHundred() {
    RiskPolicy heavy =
        new RiskPolicy(
            List.of(new RiskPolicy.AmountBand(thb("1.00"), 60)),
            List.of(new RiskPolicy.CountBand(1, 60)),
            60,
            List.of(new RiskPolicy.AgeBand(Duration.ofDays(1), 60)),
            new RiskPolicy.Thresholds(40, 70, 90));

    RiskEvaluation evaluation = heavy.evaluate(new RiskFacts(thb("5.00"), 1, false, Duration.ZERO));

    assertThat(evaluation.hits()).hasSize(4);
    assertThat(evaluation.score()).isEqualTo(100);
    assertThat(evaluation.decision()).isEqualTo(RiskDecision.BLOCK);
  }

  // Decision bands ----------------------------------------------------------------------------

  @ParameterizedTest(name = "score {0} -> {1}")
  @CsvSource({
    "0, ALLOW",
    "39, ALLOW",
    "40, MONITOR",
    "69, MONITOR",
    "70, MANUAL_REVIEW",
    "89, MANUAL_REVIEW",
    "90, BLOCK",
    "100, BLOCK"
  })
  void decisionBandEdges(int score, RiskDecision decision) {
    assertThat(policy.decide(score)).isEqualTo(decision);
  }

  /** The reachable scores next to each edge, through the rules (weights are multiples of 5). */
  @ParameterizedTest(name = "{0} THB, {1} transfers, known {2}, wallet {3}h -> {4} {5}")
  @CsvSource({
    // 20 + 15 = 35 / 30 + 10 = 40
    "25000.01, 3, true, 1000, 35, ALLOW",
    "40000.01, 1, true, 100, 40, MONITOR",
    // 30 + 35 = 65 / 30 + 25 + 15 = 70
    "40000.01, 8, true, 1000, 65, MONITOR",
    "40000.01, 5, false, 1000, 70, MANUAL_REVIEW",
    // 30 + 35 + 20 = 85 / 30 + 35 + 15 + 10 = 90
    "40000.01, 8, true, 1, 85, MANUAL_REVIEW",
    "40000.01, 8, false, 100, 90, BLOCK"
  })
  void scoresNextToEachEdgeThroughTheRules(
      String amount,
      int transfers,
      boolean known,
      long walletHours,
      int score,
      RiskDecision decision) {
    RiskEvaluation evaluation =
        policy.evaluate(
            new RiskFacts(thb(amount), transfers, known, Duration.ofHours(walletHours)));

    assertThat(evaluation.score()).isEqualTo(score);
    assertThat(evaluation.decision()).isEqualTo(decision);
  }

  @ParameterizedTest(name = "score {0}")
  @ValueSource(ints = {-1, 101})
  void aScoreOutsideTheRangeHasNoBand(int score) {
    assertThatThrownBy(() -> policy.decide(score)).isInstanceOf(IllegalArgumentException.class);
  }

  // Facts -------------------------------------------------------------------------------------

  @Test
  void factsAreChecked() {
    assertThatThrownBy(() -> new RiskFacts(Money.zero(Money.THB), 1, true, OLD_WALLET))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskFacts(thb("-1.00"), 1, true, OLD_WALLET))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskFacts(thb("1.00"), 0, true, OLD_WALLET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current");
    assertThatThrownBy(() -> new RiskFacts(thb("1.00"), 1, true, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskFacts(null, 1, true, OLD_WALLET))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RiskFacts(thb("1.00"), 1, true, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void valuesStayOutOfToString() {
    RiskFacts facts = new RiskFacts(thb("12345.67"), 3, false, Duration.ofHours(2));
    RiskEvaluation evaluation = policy.evaluate(facts);

    assertThat(facts.toString()).doesNotContain("12345");
    assertThat(evaluation.toString()).doesNotContain(Integer.toString(evaluation.score()));
    assertThat(evaluation.hits().getFirst().toString()).doesNotContain("TRANSFER_AMOUNT");
    assertThat(policy.toString()).doesNotContain("40");
  }

  @Test
  void ruleCodesAreTheEnumNames() {
    assertThat(RiskRule.values())
        .extracting(RiskRule::code)
        .containsExactly(
            "TRANSFER_AMOUNT", "TRANSFER_FREQUENCY", "NEW_RECIPIENT", "NEW_SENDER_WALLET");
  }

  @Test
  void anEvaluationIsChecked() {
    assertThatThrownBy(() -> new RiskEvaluation(101, RiskDecision.BLOCK, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskEvaluation(-1, RiskDecision.ALLOW, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleHit(RiskRule.NEW_RECIPIENT, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleHit(RiskRule.NEW_RECIPIENT, 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleHit(null, 10)).isInstanceOf(NullPointerException.class);
  }
}
