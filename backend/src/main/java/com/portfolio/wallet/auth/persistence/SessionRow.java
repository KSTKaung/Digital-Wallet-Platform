package com.portfolio.wallet.auth.persistence;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import java.time.Instant;
import java.util.UUID;

/** A session with its user's current status and role. */
public record SessionRow(
    UUID id,
    UUID userId,
    Instant lastSeenAt,
    Instant revokedAt,
    UserStatus userStatus,
    Role userRole) {}
