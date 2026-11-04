package com.portfolio.wallet.wallet.application;

import java.math.BigDecimal;

/**
 * The limits that apply to the customer's wallet, for its tier (documentations/02 §2.3; doc 06
 * MVP-ADR-18), from configuration. Amounts have scale 2.
 */
public record WalletLimitsView(
    BigDecimal dailyTransfer,
    BigDecimal dailyWithdrawal,
    BigDecimal dailyDeposit,
    BigDecimal singleTransfer,
    BigDecimal singleWithdrawal,
    BigDecimal singleDeposit,
    BigDecimal minTransfer,
    BigDecimal minWithdrawal,
    BigDecimal minDeposit,
    String currency) {}
