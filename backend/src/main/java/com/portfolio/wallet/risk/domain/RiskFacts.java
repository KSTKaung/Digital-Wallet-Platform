package com.portfolio.wallet.risk.domain;

import com.portfolio.wallet.common.money.Money;
import java.time.Duration;
import java.util.Objects;

/**
 * What the rules of one assessment look at (documentations/06 MVP-ADR-24): the {@code transfer}
 * module supplies the amount, the sender wallet's creation time and whether a completed transfer to
 * this recipient exists; the frequency count comes from the risk schema's own assessments.
 *
 * @param amount the transfer amount; positive (THB, {@link Money})
 * @param transfersInWindow the sender's assessments in the frequency window, <b>including the
 *     current one</b> and every outcome (blocked, cancelled and orphan assessments too; Q4); at
 *     least 1
 * @param completedTransferToRecipientExists whether a {@code COMPLETED} transfer from this sender
 *     to this recipient already exists; if not, the new-recipient rule applies
 * @param senderWalletAge how old the sender's wallet is at the assessment time; not negative
 */
public record RiskFacts(
    Money amount,
    int transfersInWindow,
    boolean completedTransferToRecipientExists,
    Duration senderWalletAge) {

  public RiskFacts {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(senderWalletAge, "senderWalletAge");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("amount must be positive");
    }
    if (transfersInWindow < 1) {
      throw new IllegalArgumentException("the frequency count includes the current transfer");
    }
    if (senderWalletAge.isNegative()) {
      throw new IllegalArgumentException("the sender wallet age must not be negative");
    }
  }

  /** Leaves every value out: amounts and rule inputs are never logged. */
  @Override
  public String toString() {
    return "RiskFacts[…]";
  }
}
