package com.portfolio.wallet.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailAddressTest {

  @Test
  void lowerCasesAndNormalises() {
    assertThat(EmailAddress.canonical("User@Example.TEST")).isEqualTo("user@example.test");
    assertThat(EmailAddress.canonical("É@example.test")).isEqualTo("é@example.test");
  }

  @Test
  void isStable() {
    String once = EmailAddress.canonical("Éa@Example.test");
    assertThat(EmailAddress.canonical(once)).isEqualTo(once);
  }
}
