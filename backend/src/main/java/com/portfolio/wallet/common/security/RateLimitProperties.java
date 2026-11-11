package com.portfolio.wallet.common.security;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * In-memory rate limits per minute (documentations/02 §1.8; doc 06 MVP-ADR-06). Correct for one
 * instance only.
 *
 * @param moneyRequestsPerMinutePerCustomer money POSTs, on top of the per-user limit: 30 (doc 02
 *     §1.8). Counted for {@code POST /wallet/deposits}, {@code POST /wallet/withdrawals} and {@code
 *     POST /transfers}; {@code POST /transfers/{id}/cancel} and the transfer-review actions count
 *     only toward {@code otherRequestsPerMinutePerUser} (doc 06 MVP-ADR-24 amendment "Rate limit
 *     (Q15)"; doc 08 §4C.2 Q15)
 * @param maxTrackedKeys the most keys each limiter holds, which bounds its memory
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    @Positive int authRequestsPerMinutePerIp,
    @Positive int otherRequestsPerMinutePerUser,
    @Positive int moneyRequestsPerMinutePerCustomer,
    @Positive int maxTrackedKeys) {}
