package com.portfolio.wallet.wallet.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A completed deposit or withdrawal as returned to its customer (documentations/02 §2.3 {@code
 * DepositResponse} / {@code WithdrawalResponse}; doc 06 MVP-ADR-10 amendment: the wallet number,
 * never the internal wallet id). Also the body stored on the idempotency record and replayed.
 *
 * @param amount scale 2 (MVP-ADR-21)
 * @param status {@code COMPLETED}, the only status in the MVP (doc 07 D10)
 * @param reference the server-generated unique reference (doc 07 D10)
 * @param ledgerStatus {@code POSTED}: the response reaches the client only after the commit that
 *     posted the ledger transaction (MVP-ADR-03)
 */
public record MoneyMovementView(
    UUID id,
    String walletNumber,
    BigDecimal amount,
    String status,
    String reference,
    String ledgerStatus) {

  /** The wallet number is the key of the recipient lookup (MVP-ADR-20), so it is left out. */
  @Override
  public String toString() {
    return "MoneyMovementView[id=" + id + ", status=" + status + "]";
  }
}
