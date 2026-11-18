package com.portfolio.wallet.transaction.web;

import com.portfolio.wallet.transaction.application.TransactionView;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One item of {@code GET /transactions}: {@code {reference, type, amount, currency, postedAt,
 * ledgerStatus}} (documentations/02 §2.4 "MVP history"; doc 06 MVP-ADR-11 amendment).
 */
record TransactionResponse(
    String reference,
    String type,
    BigDecimal amount,
    String currency,
    Instant postedAt,
    String ledgerStatus) {

  static TransactionResponse from(TransactionView view) {
    return new TransactionResponse(
        view.reference(),
        view.type(),
        view.amount(),
        view.currency(),
        view.postedAt(),
        view.ledgerStatus());
  }
}
