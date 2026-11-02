package com.portfolio.wallet.auth.persistence;

import com.portfolio.wallet.auth.domain.UserStatus;
import java.util.UUID;

/** A user row held under a lock, with the state read after the lock was granted. */
public record LockedUser(UUID id, UserStatus status) {}
