package com.portfolio.wallet.wallet.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A transfer whose money moved to the recipient: completed directly, or captured from its hold on
 * approval (MVP-ADR-03, -24).
 *
 * @param limitDate the Asia/Bangkok day the amount counts on: the current day for a completion, the
 *     hold's stored day for a capture (MVP-ADR-22); the transfer row stores it as {@code
 *     limit_date}
 * @param ledgerTransactionId the balanced {@code TRANSFER} ledger transaction posted in the same
 *     database transaction
 */
public record TransferSettlement(LocalDate limitDate, UUID ledgerTransactionId) {

  public TransferSettlement {
    Objects.requireNonNull(limitDate, "limitDate");
    Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId");
  }
}
