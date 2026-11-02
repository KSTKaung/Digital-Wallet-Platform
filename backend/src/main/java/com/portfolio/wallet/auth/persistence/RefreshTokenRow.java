package com.portfolio.wallet.auth.persistence;

import java.time.Instant;
import java.util.UUID;

/** A stored refresh token, without its hash. */
public record RefreshTokenRow(
    UUID id, UUID sessionId, Instant expiresAt, Instant usedAt, Instant revokedAt) {}
