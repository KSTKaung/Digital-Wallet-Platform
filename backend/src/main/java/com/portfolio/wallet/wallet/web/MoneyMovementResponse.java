package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.wallet.application.MoneyMovementView;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code DepositResponse} / {@code WithdrawalResponse} (documentations/02 §2.3): {@code {id,
 * walletNumber, amount, status, reference, ledgerStatus}}; the amount is a JSON number with two
 * decimals, and the internal wallet id is not exposed (doc 06 MVP-ADR-10 amendment).
 */
record MoneyMovementResponse(
    UUID id,
    String walletNumber,
    BigDecimal amount,
    String status,
    String reference,
    String ledgerStatus) {

  static MoneyMovementResponse from(MoneyMovementView view) {
    return new MoneyMovementResponse(
        view.id(),
        view.walletNumber(),
        view.amount(),
        view.status(),
        view.reference(),
        view.ledgerStatus());
  }

  /** The wallet number is the key of the recipient lookup (MVP-ADR-20), so it is left out. */
  @Override
  public String toString() {
    return "MoneyMovementResponse[id=" + id + ", status=" + status + "]";
  }
}
