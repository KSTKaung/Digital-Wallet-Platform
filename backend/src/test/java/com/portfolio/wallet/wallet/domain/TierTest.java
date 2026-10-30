package com.portfolio.wallet.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** documentations/08 Q9; doc 01 §7.5. */
class TierTest {

  @Test
  void mapsKycLevelsToTiers() {
    assertThat(Tier.forKycLevel("BASIC")).isEqualTo(Tier.BASIC);
    assertThat(Tier.forKycLevel("FULL")).isEqualTo(Tier.VERIFIED);
  }

  @Test
  void rejectsUnknownLevels() {
    assertThatThrownBy(() -> Tier.forKycLevel("PREMIUM"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Tier.forKycLevel("VERIFIED"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
