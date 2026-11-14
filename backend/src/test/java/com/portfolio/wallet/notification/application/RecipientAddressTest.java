package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The send-time lookup result (owner, 2026-11-14, decision 8). */
class RecipientAddressTest {

  @Test
  void onlyAnActiveAccountHasAnAddressAndToStringNeverShowsIt() {
    RecipientAddress active = RecipientAddress.active("o@example.test");

    assertThat(active.outcome()).isEqualTo(RecipientAddress.Outcome.ACTIVE);
    assertThat(active.email()).contains("o@example.test");
    assertThat(active).isEqualTo(RecipientAddress.active("o@example.test"));
    assertThat(active.hashCode()).isEqualTo(RecipientAddress.active("o@example.test").hashCode());
    assertThat(active).isNotEqualTo(RecipientAddress.active("p@example.test"));
    assertThat(active.toString()).isEqualTo("RecipientAddress[ACTIVE]");

    assertThat(RecipientAddress.notActive().outcome())
        .isEqualTo(RecipientAddress.Outcome.NOT_ACTIVE);
    assertThat(RecipientAddress.notActive().email()).isEmpty();
    assertThat(RecipientAddress.notFound().outcome()).isEqualTo(RecipientAddress.Outcome.NOT_FOUND);
    assertThat(RecipientAddress.notFound().email()).isEmpty();
    assertThat(RecipientAddress.notActive()).isNotEqualTo(RecipientAddress.notFound());
  }

  @Test
  void anActiveAddressMustBePresent() {
    assertThatThrownBy(() -> RecipientAddress.active(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RecipientAddress.active(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
