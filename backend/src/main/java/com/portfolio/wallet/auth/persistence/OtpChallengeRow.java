package com.portfolio.wallet.auth.persistence;

import com.portfolio.wallet.auth.domain.OtpChallengeState;
import java.util.UUID;

/** A stored challenge. {@link #toString()} leaves the hash out. */
public record OtpChallengeRow(UUID id, String otpHash, OtpChallengeState state) {

  @Override
  public String toString() {
    return "OtpChallengeRow[id=" + id + "]";
  }
}
