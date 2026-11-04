package com.portfolio.wallet.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Freeze and unfreeze of a ledger account (V2 statuses; doc 06 MVP-ADR-10 amendment). */
class LedgerAccountStatusTransitionTest {

  private static LedgerAccount in(LedgerAccountStatus status) {
    return LedgerAccount.restore(
        UUID.randomUUID(), null, AccountType.CUSTOMER, UUID.randomUUID(), Money.THB, status);
  }

  @Test
  void freezeAndUnfreezeKeepTheIdentityAndOnlyFlipTheStatus() {
    LedgerAccount active = in(LedgerAccountStatus.ACTIVE);

    LedgerAccount frozen = active.freeze();
    assertThat(frozen.status()).isEqualTo(LedgerAccountStatus.FROZEN);
    assertThat(frozen.acceptsPostings()).isFalse();
    assertThat(frozen.id()).isEqualTo(active.id());
    assertThat(frozen.ownerReference()).isEqualTo(active.ownerReference());
    assertThat(frozen.type()).isEqualTo(AccountType.CUSTOMER);
    assertThat(active.status()).as("unchanged").isEqualTo(LedgerAccountStatus.ACTIVE);

    LedgerAccount unfrozen = frozen.unfreeze();
    assertThat(unfrozen.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
    assertThat(unfrozen.acceptsPostings()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(value = LedgerAccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
  void onlyAnActiveAccountCanBeFrozen(LedgerAccountStatus status) {
    assertThatThrownBy(() -> in(status).freeze())
        .isInstanceOf(InvalidLedgerAccountStatusTransitionException.class);
  }

  @ParameterizedTest
  @EnumSource(value = LedgerAccountStatus.class, names = "FROZEN", mode = EnumSource.Mode.EXCLUDE)
  void onlyAFrozenAccountCanBeUnfrozenSoClosedIsFinal(LedgerAccountStatus status) {
    assertThatThrownBy(() -> in(status).unfreeze())
        .isInstanceOf(InvalidLedgerAccountStatusTransitionException.class);
  }
}
