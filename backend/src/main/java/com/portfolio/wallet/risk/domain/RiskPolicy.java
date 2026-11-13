package com.portfolio.wallet.risk.domain;

import com.portfolio.wallet.common.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The risk score of a transfer (documentations/06 MVP-ADR-24 "Score" and "Bands"; doc 07 D27).
 *
 * <ul>
 *   <li>Each rule has bands; <b>only the highest matching weight per rule</b> applies, and a rule
 *       with a matching band is one {@link RuleHit}.
 *   <li>The score is the sum of the hits' weights, <b>capped at {@value #MAX_SCORE}</b>.
 *   <li>The decision is the band of the score: {@code BLOCK} from {@link Thresholds#block()},
 *       {@code MANUAL_REVIEW} from {@link Thresholds#manualReview()}, {@code MONITOR} from {@link
 *       Thresholds#monitor()}, otherwise {@code ALLOW}.
 * </ul>
 *
 * Every weight and boundary is configuration ({@code app.risk}); this class holds no business
 * value. The constructor checks that the configuration is well formed: weights 1–100, band
 * boundaries strictly ascending, weights that grow with the risk (larger amounts and more transfers
 * weigh more, an older wallet weighs less), and thresholds {@code 1 ≤ monitor < manualReview <
 * block ≤ 100}, so a score of 0 (no rule hit) is always {@code ALLOW}.
 *
 * <p>Pure Java (ArchUnit {@code financialDomainIsFrameworkFree}); nothing here is logged.
 */
public final class RiskPolicy {

  /** The highest score (V5 {@code ck_risk_assessments_score}). */
  public static final int MAX_SCORE = 100;

  /** The weight range of one rule hit (V5 {@code ck_risk_rule_hits_weight}). */
  public static final int MIN_WEIGHT = 1;

  public static final int MAX_WEIGHT = 100;

  /**
   * An amount band: the rule matches amounts of at least {@code minAmount}. The documented band
   * "10,000.01–25,000.00" is the band from 10,000.01; its upper end is the next band's start.
   */
  public record AmountBand(Money minAmount, int weight) {

    public AmountBand {
      Objects.requireNonNull(minAmount, "minAmount");
      if (!minAmount.isPositive()) {
        throw new IllegalArgumentException("amount band min-amount must be positive");
      }
      requireWeight(weight, "amount band weight");
    }
  }

  /** A frequency band: the rule matches at least {@code minCount} transfers in the window. */
  public record CountBand(int minCount, int weight) {

    public CountBand {
      if (minCount < 1) {
        throw new IllegalArgumentException("frequency band min-count must be at least 1");
      }
      requireWeight(weight, "frequency band weight");
    }
  }

  /** A wallet-age band: the rule matches a sender wallet younger than {@code youngerThan}. */
  public record AgeBand(Duration youngerThan, int weight) {

    public AgeBand {
      Objects.requireNonNull(youngerThan, "youngerThan");
      if (youngerThan.isNegative() || youngerThan.isZero()) {
        throw new IllegalArgumentException("wallet age band younger-than must be positive");
      }
      requireWeight(weight, "wallet age band weight");
    }
  }

  /** The first score of each band above {@code ALLOW}. */
  public record Thresholds(int monitor, int manualReview, int block) {

    public Thresholds {
      if (monitor < 1 || block > MAX_SCORE || monitor >= manualReview || manualReview >= block) {
        throw new IllegalArgumentException(
            "thresholds must satisfy 1 <= monitor < manual-review < block <= 100");
      }
    }
  }

  private final List<AmountBand> amountBands;
  private final List<CountBand> frequencyBands;
  private final int newRecipientWeight;
  private final List<AgeBand> newWalletBands;
  private final Thresholds thresholds;

  /**
   * @throws IllegalArgumentException if a list is empty, the boundaries of a list do not strictly
   *     ascend, or its weights do not move with the risk as described on the class
   */
  public RiskPolicy(
      List<AmountBand> amountBands,
      List<CountBand> frequencyBands,
      int newRecipientWeight,
      List<AgeBand> newWalletBands,
      Thresholds thresholds) {
    this.amountBands = nonEmpty(amountBands, "amount bands");
    this.frequencyBands = nonEmpty(frequencyBands, "frequency bands");
    this.newWalletBands = nonEmpty(newWalletBands, "wallet age bands");
    this.newRecipientWeight = requireWeight(newRecipientWeight, "new recipient weight");
    this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    for (int i = 1; i < this.amountBands.size(); i++) {
      AmountBand previous = this.amountBands.get(i - 1);
      AmountBand band = this.amountBands.get(i);
      if (!band.minAmount().isGreaterThan(previous.minAmount())
          || band.weight() <= previous.weight()) {
        throw new IllegalArgumentException("amount bands must ascend in min-amount and in weight");
      }
    }
    for (int i = 1; i < this.frequencyBands.size(); i++) {
      CountBand previous = this.frequencyBands.get(i - 1);
      CountBand band = this.frequencyBands.get(i);
      if (band.minCount() <= previous.minCount() || band.weight() <= previous.weight()) {
        throw new IllegalArgumentException(
            "frequency bands must ascend in min-count and in weight");
      }
    }
    for (int i = 1; i < this.newWalletBands.size(); i++) {
      AgeBand previous = this.newWalletBands.get(i - 1);
      AgeBand band = this.newWalletBands.get(i);
      if (band.youngerThan().compareTo(previous.youngerThan()) <= 0
          || band.weight() >= previous.weight()) {
        throw new IllegalArgumentException(
            "wallet age bands must ascend in younger-than and descend in weight");
      }
    }
  }

  private static <T> List<T> nonEmpty(List<T> list, String name) {
    Objects.requireNonNull(list, name);
    if (list.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    list.forEach(element -> Objects.requireNonNull(element, name));
    return List.copyOf(list);
  }

  static int requireWeight(int weight, String name) {
    if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
      throw new IllegalArgumentException(name + " must be between 1 and 100");
    }
    return weight;
  }

  /** Scores the facts: the hits, their capped sum and its decision band. */
  public RiskEvaluation evaluate(RiskFacts facts) {
    Objects.requireNonNull(facts, "facts");
    List<RuleHit> hits = new ArrayList<>(RiskRule.values().length);
    highest(
            amountBands,
            b -> facts.amount().isGreaterThanOrEqualTo(b.minAmount()),
            AmountBand::weight)
        .ifPresent(weight -> hits.add(new RuleHit(RiskRule.TRANSFER_AMOUNT, weight)));
    highest(frequencyBands, b -> facts.transfersInWindow() >= b.minCount(), CountBand::weight)
        .ifPresent(weight -> hits.add(new RuleHit(RiskRule.TRANSFER_FREQUENCY, weight)));
    if (!facts.completedTransferToRecipientExists()) {
      hits.add(new RuleHit(RiskRule.NEW_RECIPIENT, newRecipientWeight));
    }
    highest(
            newWalletBands,
            b -> facts.senderWalletAge().compareTo(b.youngerThan()) < 0,
            AgeBand::weight)
        .ifPresent(weight -> hits.add(new RuleHit(RiskRule.NEW_SENDER_WALLET, weight)));
    int sum = hits.stream().mapToInt(RuleHit::weight).sum();
    int score = Math.min(sum, MAX_SCORE);
    return new RiskEvaluation(score, decide(score), hits);
  }

  /** The decision band of a score of 0–100. */
  public RiskDecision decide(int score) {
    if (score < 0 || score > MAX_SCORE) {
      throw new IllegalArgumentException("score must be between 0 and 100");
    }
    if (score >= thresholds.block()) {
      return RiskDecision.BLOCK;
    }
    if (score >= thresholds.manualReview()) {
      return RiskDecision.MANUAL_REVIEW;
    }
    if (score >= thresholds.monitor()) {
      return RiskDecision.MONITOR;
    }
    return RiskDecision.ALLOW;
  }

  /** The highest weight among the matching bands of one rule; empty if none matches. */
  private static <B> OptionalInt highest(
      List<B> bands, Predicate<B> matches, ToIntFunction<B> weight) {
    return bands.stream().filter(matches).mapToInt(weight).max();
  }

  /** Leaves the configured values out of logs. */
  @Override
  public String toString() {
    return "RiskPolicy[…]";
  }
}
