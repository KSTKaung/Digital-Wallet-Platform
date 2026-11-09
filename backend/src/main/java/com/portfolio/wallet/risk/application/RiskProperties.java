package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.risk.domain.RiskPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Risk rule weights, bands and decision thresholds ({@code app.risk}; documentations/06 MVP-ADR-24
 * "Score" and "Bands", owner 2026-10-30, doc 07 D27). The values live in configuration only: no
 * weight or threshold is a literal in code. Checked when the application starts, so a bad value
 * stops the start instead of reaching an assessment:
 *
 * <ul>
 *   <li>every value is present; amounts THB with at most 2 decimal places (MVP-ADR-21);
 *   <li>weights 1–100; band boundaries strictly ascending; weights grow with the risk (amount and
 *       frequency weights ascend, wallet-age weights descend as the age grows);
 *   <li>thresholds {@code 1 ≤ monitor < manual-review < block ≤ 100};
 *   <li>the frequency window is whole seconds, at least 1 second.
 * </ul>
 *
 * The ordering checks are {@link RiskPolicy}'s; messages name the property, never a value.
 *
 * @param amount the transfer-amount rule
 * @param frequency the transfer-frequency rule and its window
 * @param newRecipient the new-recipient rule
 * @param newSenderWallet the new-wallet rule (the sender's wallet)
 * @param thresholds the first score of each decision band above {@code ALLOW}
 */
@ConfigurationProperties(prefix = "app.risk")
public record RiskProperties(
    Amount amount,
    Frequency frequency,
    NewRecipient newRecipient,
    NewSenderWallet newSenderWallet,
    Thresholds thresholds) {

  private static final String PREFIX = "app.risk.";

  public RiskProperties {
    require(amount, "amount");
    require(frequency, "frequency");
    require(newRecipient, "new-recipient");
    require(newSenderWallet, "new-sender-wallet");
    require(thresholds, "thresholds");
    // Builds the policy once, so its ordering checks fail the start.
    toPolicy(amount, frequency, newRecipient, newSenderWallet, thresholds);
  }

  /**
   * @param bands each band applies from its {@code min-amount} (inclusive) up to the next band's
   */
  public record Amount(List<AmountBand> bands) {

    public Amount {
      requireList(bands, "amount.bands");
    }
  }

  /**
   * @param minAmount the smallest amount of the band, THB, at most 2 decimals; MVP: 10000.01,
   *     25000.01, 40000.01 (above 40,000.00)
   * @param weight the band's weight
   */
  public record AmountBand(BigDecimal minAmount, int weight) {

    public AmountBand {
      require(minAmount, "amount.bands[].min-amount");
    }
  }

  /**
   * @param window how far back the sender's assessments count: 10 minutes; whole seconds
   * @param bands each band applies from its {@code min-count} (inclusive), the current transfer
   *     included
   */
  public record Frequency(Duration window, List<CountBand> bands) {

    public Frequency {
      if (window == null
          || window.compareTo(Duration.ofSeconds(1)) < 0
          || window.toNanosPart() != 0) {
        throw new IllegalArgumentException(
            PREFIX + "frequency.window must be whole seconds, at least 1s");
      }
      requireList(bands, "frequency.bands");
    }
  }

  /**
   * @param minCount the smallest count of the band; MVP: 3, 5, 8
   * @param weight the band's weight
   */
  public record CountBand(int minCount, int weight) {}

  /**
   * @param weight the weight when no completed transfer from this sender to this recipient exists
   */
  public record NewRecipient(int weight) {}

  /**
   * @param bands each band applies to a wallet younger than its {@code younger-than}
   */
  public record NewSenderWallet(List<AgeBand> bands) {

    public NewSenderWallet {
      requireList(bands, "new-sender-wallet.bands");
    }
  }

  /**
   * @param youngerThan the band applies below this age; MVP: 24h, 7d
   * @param weight the band's weight
   */
  public record AgeBand(Duration youngerThan, int weight) {

    public AgeBand {
      require(youngerThan, "new-sender-wallet.bands[].younger-than");
    }
  }

  /**
   * @param monitor the first {@code MONITOR} score: 40
   * @param manualReview the first {@code MANUAL_REVIEW} score: 70
   * @param block the first {@code BLOCK} score: 90
   */
  public record Thresholds(int monitor, int manualReview, int block) {}

  /** The policy every assessment uses. */
  public RiskPolicy toPolicy() {
    return toPolicy(amount, frequency, newRecipient, newSenderWallet, thresholds);
  }

  /** The frequency window in whole seconds, for the database count. */
  public long frequencyWindowSeconds() {
    return frequency.window().toSeconds();
  }

  private static RiskPolicy toPolicy(
      Amount amount,
      Frequency frequency,
      NewRecipient newRecipient,
      NewSenderWallet newSenderWallet,
      Thresholds thresholds) {
    try {
      return new RiskPolicy(
          amount.bands().stream()
              .map(b -> new RiskPolicy.AmountBand(money(b.minAmount()), b.weight()))
              .toList(),
          frequency.bands().stream()
              .map(b -> new RiskPolicy.CountBand(b.minCount(), b.weight()))
              .toList(),
          newRecipient.weight(),
          newSenderWallet.bands().stream()
              .map(b -> new RiskPolicy.AgeBand(b.youngerThan(), b.weight()))
              .toList(),
          new RiskPolicy.Thresholds(
              thresholds.monitor(), thresholds.manualReview(), thresholds.block()));
    } catch (IllegalArgumentException e) {
      // The policy's messages name the setting, never a value; no cause, so the root cause of the
      // startup failure names the prefix.
      throw new IllegalArgumentException("app.risk: " + e.getMessage());
    }
  }

  private static Money money(BigDecimal value) {
    try {
      return Money.of(value, Money.THB);
    } catch (InvalidAmountException e) {
      throw new IllegalArgumentException(
          "amount.bands[].min-amount must be a THB amount with at most 2 decimal places");
    }
  }

  private static void require(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(PREFIX + name + " is required");
    }
  }

  private static void requireList(List<?> list, String name) {
    if (list == null || list.isEmpty()) {
      throw new IllegalArgumentException(PREFIX + name + " must not be empty");
    }
    if (list.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(PREFIX + name + " must not contain an empty entry");
    }
  }
}
