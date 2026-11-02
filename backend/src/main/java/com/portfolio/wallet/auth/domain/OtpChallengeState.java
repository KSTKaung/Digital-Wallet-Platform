package com.portfolio.wallet.auth.domain;

import java.time.Instant;

/** The parts of a stored challenge that decide whether it can still accept a code. */
public record OtpChallengeState(
    int attempts, Instant expiresAt, Instant consumedAt, Instant cancelledAt, Instant usedUpAt) {}
