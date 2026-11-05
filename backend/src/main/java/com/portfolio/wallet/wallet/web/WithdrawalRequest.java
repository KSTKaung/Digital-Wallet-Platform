package com.portfolio.wallet.wallet.web;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/v1/wallet/withdrawals} (documentations/02 §2.3). Amount scale, sign and
 * currency are checked by the service ({@code INVALID_AMOUNT}, {@code CURRENCY_MISMATCH}).
 */
record WithdrawalRequest(@NotNull BigDecimal amount, @NotNull String currency) {

  @Override
  public String toString() {
    return "WithdrawalRequest[]";
  }
}
