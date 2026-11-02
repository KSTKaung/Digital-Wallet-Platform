package com.portfolio.wallet.auth.persistence;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import java.time.Instant;
import java.util.UUID;

/** A user with the credential fields login needs. {@link #toString()} leaves the hash out. */
public record AccountRow(
    UUID id, String email, UserStatus status, Role role, String passwordHash, Instant lockedUntil) {

  @Override
  public String toString() {
    return "AccountRow[id=" + id + "]";
  }
}
