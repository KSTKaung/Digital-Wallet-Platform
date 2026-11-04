package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.wallet.application.WalletLimitsView;
import java.math.BigDecimal;

/**
 * {@code GET /wallet/limits} (documentations/02 §2.3), the doc's fields plus {@code currency},
 * which every amount carries (MVP-ADR-21).
 */
record WalletLimitsResponse(
    BigDecimal dailyTransfer,
    BigDecimal dailyWithdrawal,
    BigDecimal dailyDeposit,
    BigDecimal singleTransfer,
    BigDecimal singleWithdrawal,
    BigDecimal singleDeposit,
    BigDecimal minTransfer,
    BigDecimal minWithdrawal,
    BigDecimal minDeposit,
    String currency) {

  static WalletLimitsResponse from(WalletLimitsView view) {
    return new WalletLimitsResponse(
        view.dailyTransfer(),
        view.dailyWithdrawal(),
        view.dailyDeposit(),
        view.singleTransfer(),
        view.singleWithdrawal(),
        view.singleDeposit(),
        view.minTransfer(),
        view.minWithdrawal(),
        view.minDeposit(),
        view.currency());
  }
}
