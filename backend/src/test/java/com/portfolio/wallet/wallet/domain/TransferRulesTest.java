package com.portfolio.wallet.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.Money;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Sender rules of a transfer on every boundary (documentations/06 MVP-ADR-18 values as configured
 * in {@code application.yaml}; doc 08 §4C.4 criterion 2): minimum 1.00, maximum 50,000.00, daily
 * {@code BASIC} 100,000.00 and {@code VERIFIED} 500,000.00, counting used + reserved.
 */
class TransferRulesTest {

  private static final TransferRules RULES = new TransferRules(policy());

  private static LimitPolicy policy() {
    Map<Operation, OperationLimits> limits = new EnumMap<>(Operation.class);
    limits.put(
        Operation.DEPOSIT,
        OperationLimits.sameForEveryTier(m("100.00"), m("100000.00"), m("200000.00")));
    limits.put(
        Operation.WITHDRAWAL,
        OperationLimits.sameForEveryTier(m("100.00"), m("30000.00"), m("50000.00")));
    Map<Tier, Money> daily = new EnumMap<>(Tier.class);
    daily.put(Tier.BASIC, m("100000.00"));
    daily.put(Tier.VERIFIED, m("500000.00"));
    limits.put(Operation.TRANSFER, new OperationLimits(m("1.00"), m("50000.00"), daily));
    return new LimitPolicy(limits);
  }

  private static Money m(String amount) {
    return Money.of(amount, Money.THB);
  }

  private static Wallet wallet(WalletStatus status, Tier tier, String available, String reserved) {
    return Wallet.restore(
        UUID.randomUUID(),
        WalletNumber.parse("12345678903"),
        UUID.randomUUID(),
        tier,
        status,
        m(available),
        m(reserved),
        3);
  }

  private static TransferCheckResult check(
      Tier tier, String available, String amount, String used, String reserved) {
    return RULES.check(
        wallet(WalletStatus.ACTIVE, tier, available, "0.00"), m(amount), m(used), m(reserved));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "0.99, BELOW_MINIMUM",
    "1.00, ALLOWED",
    "1.01, ALLOWED",
    "49999.99, ALLOWED",
    "50000.00, ALLOWED",
    "50000.01, SINGLE_LIMIT_EXCEEDED"
  })
  void minimumAndSingleLimitBoundaries(String amount, TransferCheckResult expected) {
    assertThat(check(Tier.BASIC, "1000000.00", amount, "0.00", "0.00")).isEqualTo(expected);
    assertThat(check(Tier.VERIFIED, "1000000.00", amount, "0.00", "0.00")).isEqualTo(expected);
  }

  /** The day's used and reserved amounts both count, up to the tier's limit inclusive. */
  @ParameterizedTest(name = "{0}: used {1} + reserved {2} + {3} -> {4}")
  @CsvSource({
    "BASIC, 60000.00, 30000.00, 10000.00, ALLOWED",
    "BASIC, 60000.00, 30000.00, 10000.01, DAILY_LIMIT_EXCEEDED",
    "BASIC, 0.00, 99999.99, 0.01, BELOW_MINIMUM",
    "BASIC, 99999.00, 0.00, 1.00, ALLOWED",
    "BASIC, 99999.01, 0.00, 1.00, DAILY_LIMIT_EXCEEDED",
    "BASIC, 0.00, 99999.01, 1.00, DAILY_LIMIT_EXCEEDED",
    "BASIC, 50000.00, 0.00, 50000.00, ALLOWED",
    "BASIC, 50000.00, 0.01, 50000.00, DAILY_LIMIT_EXCEEDED",
    "VERIFIED, 400000.00, 50000.00, 50000.00, ALLOWED",
    "VERIFIED, 400000.00, 50000.01, 50000.00, DAILY_LIMIT_EXCEEDED",
    "VERIFIED, 450000.01, 0.00, 50000.00, DAILY_LIMIT_EXCEEDED",
    "VERIFIED, 100000.00, 0.00, 50000.00, ALLOWED"
  })
  void dailyLimitCountsUsedAndReservedPerTier(
      Tier tier, String used, String reserved, String amount, TransferCheckResult expected) {
    assertThat(check(tier, "1000000.00", amount, used, reserved)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "available {0}, amount {1} -> {2}")
  @CsvSource({
    "100.00, 100.00, ALLOWED",
    "100.00, 100.01, INSUFFICIENT_BALANCE",
    "0.00, 1.00, INSUFFICIENT_BALANCE"
  })
  void onlyTheAvailableBalanceCounts(
      String available, String amount, TransferCheckResult expected) {
    assertThat(check(Tier.BASIC, available, amount, "0.00", "0.00")).isEqualTo(expected);
    // A reserved balance is never spendable.
    assertThat(
            RULES.check(
                wallet(WalletStatus.ACTIVE, Tier.BASIC, available, "5000.00"),
                m(amount),
                m("0.00"),
                m("0.00")))
        .isEqualTo(expected);
  }

  /** The documented order: status, minimum, single, daily, balance (Q3 step 5). */
  @Test
  void theFirstFailingRuleIsReported() {
    Money tooMuch = m("50000.01");
    assertThat(
            RULES.check(
                wallet(WalletStatus.FROZEN, Tier.BASIC, "0.00", "0.00"),
                m("0.01"),
                m("100000.00"),
                m("0.00")))
        .isEqualTo(TransferCheckResult.SENDER_FROZEN);
    assertThat(
            RULES.check(
                wallet(WalletStatus.CLOSED, Tier.BASIC, "0.00", "0.00"),
                m("0.01"),
                m("0.00"),
                m("0.00")))
        .isEqualTo(TransferCheckResult.SENDER_CLOSED);
    assertThat(check(Tier.BASIC, "0.00", "0.99", "100000.00", "0.00"))
        .isEqualTo(TransferCheckResult.BELOW_MINIMUM);
    assertThat(
            RULES.check(
                wallet(WalletStatus.ACTIVE, Tier.BASIC, "0.00", "0.00"),
                tooMuch,
                m("100000.00"),
                m("0.00")))
        .isEqualTo(TransferCheckResult.SINGLE_LIMIT_EXCEEDED);
    assertThat(check(Tier.BASIC, "0.00", "1.00", "100000.00", "0.00"))
        .isEqualTo(TransferCheckResult.DAILY_LIMIT_EXCEEDED);
  }

  @Test
  void argumentsAreRequired() {
    assertThatThrownBy(() -> new TransferRules(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RULES.check(null, m("1.00"), m("0.00"), m("0.00")))
        .isInstanceOf(NullPointerException.class);
    Wallet wallet = wallet(WalletStatus.ACTIVE, Tier.BASIC, "1.00", "0.00");
    assertThatThrownBy(() -> RULES.check(wallet, null, m("0.00"), m("0.00")))
        .isInstanceOf(NullPointerException.class);
  }
}
