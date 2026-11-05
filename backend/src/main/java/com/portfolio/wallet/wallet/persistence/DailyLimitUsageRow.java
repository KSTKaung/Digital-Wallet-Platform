package com.portfolio.wallet.wallet.persistence;

import java.math.BigDecimal;

/**
 * One locked {@code wallet.daily_limit_usage} row (V3).
 *
 * @param used completed movements on the day
 * @param reserved amounts held by transfers under review on the day (MVP-ADR-24)
 */
public record DailyLimitUsageRow(BigDecimal used, BigDecimal reserved, long version) {}
