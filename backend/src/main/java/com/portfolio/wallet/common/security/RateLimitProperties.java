package com.portfolio.wallet.common.security;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * In-memory rate limits per minute (documentations/02 §1.8; doc 06 MVP-ADR-06). Correct for one
 * instance only.
 *
 * @param maxTrackedKeys the most keys each limiter holds, which bounds its memory
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    @Positive int authRequestsPerMinutePerIp,
    @Positive int otherRequestsPerMinutePerUser,
    @Positive int maxTrackedKeys) {}
