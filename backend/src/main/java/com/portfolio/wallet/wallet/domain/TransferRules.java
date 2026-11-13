package com.portfolio.wallet.wallet.domain;

import com.portfolio.wallet.common.money.Money;
import java.util.Objects;

/**
 * The sender-side rules of an outgoing transfer (documentations/06 MVP-ADR-18, -24; doc 08 §4C.2 Q3
 * step 5, §4C.4 criterion 2), in the documented order:
 *
 * <ol>
 *   <li>the sender wallet's status: {@code FROZEN} ({@code WALLET_FROZEN}), {@code CLOSED};
 *   <li>the {@link Operation#TRANSFER} limits of the sender's tier ({@link LimitPolicy#check}):
 *       minimum, per-transaction maximum, then the day's used + reserved + amount against the daily
 *       limit, every boundary inclusive;
 *   <li>the available balance, never the reserved one: no overdraft.
 * </ol>
 *
 * The same rules serve the read-only pre-check before the risk assessment and the authoritative
 * check under the wallet and usage locks; only the inputs differ (committed now, or locked). The
 * caller supplies the usage of the right Asia/Bangkok day (MVP-ADR-22). Values come from the
 * policy, never literals.
 */
public final class TransferRules {

  private final LimitPolicy limits;

  public TransferRules(LimitPolicy limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /**
   * @param usedToday the sender's completed transfers on the business day (≥ 0)
   * @param reservedToday the sender's held transfers on the business day (≥ 0; MVP-ADR-24)
   */
  public TransferCheckResult check(
      Wallet sender, Money amount, Money usedToday, Money reservedToday) {
    Objects.requireNonNull(sender, "sender");
    Objects.requireNonNull(amount, "amount");
    switch (sender.status()) {
      case FROZEN -> {
        return TransferCheckResult.SENDER_FROZEN;
      }
      case CLOSED -> {
        return TransferCheckResult.SENDER_CLOSED;
      }
      case ACTIVE -> {}
    }
    switch (limits.check(Operation.TRANSFER, sender.tier(), amount, usedToday, reservedToday)) {
      case BELOW_MINIMUM -> {
        return TransferCheckResult.BELOW_MINIMUM;
      }
      case SINGLE_LIMIT_EXCEEDED -> {
        return TransferCheckResult.SINGLE_LIMIT_EXCEEDED;
      }
      case DAILY_LIMIT_EXCEEDED -> {
        return TransferCheckResult.DAILY_LIMIT_EXCEEDED;
      }
      case WITHIN_LIMITS -> {}
    }
    if (sender.availableBalance().isLessThan(amount)) {
      return TransferCheckResult.INSUFFICIENT_BALANCE;
    }
    return TransferCheckResult.ALLOWED;
  }
}
