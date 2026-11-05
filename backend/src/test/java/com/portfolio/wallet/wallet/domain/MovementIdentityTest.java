package com.portfolio.wallet.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Q14 (documentations/08 §4B.2): customer, operation, wallet, amount and currency all match. */
class MovementIdentityTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID WALLET = UUID.randomUUID();

  private static MovementIdentity identity(
      UUID customer, Operation operation, UUID wallet, String amount) {
    return new MovementIdentity(
        customer, operation, wallet, Money.of(new BigDecimal(amount), Money.THB));
  }

  private static final MovementIdentity STORED =
      identity(CUSTOMER, Operation.DEPOSIT, WALLET, "100.00");

  @Test
  void sameMovementWhenEveryFieldMatches() {
    assertThat(identity(CUSTOMER, Operation.DEPOSIT, WALLET, "100").isSameMovementAs(STORED))
        .isTrue();
  }

  @Test
  void anyDifferenceIsAnotherMovement() {
    assertThat(
            identity(UUID.randomUUID(), Operation.DEPOSIT, WALLET, "100").isSameMovementAs(STORED))
        .isFalse();
    assertThat(identity(CUSTOMER, Operation.WITHDRAWAL, WALLET, "100").isSameMovementAs(STORED))
        .isFalse();
    assertThat(
            identity(CUSTOMER, Operation.DEPOSIT, UUID.randomUUID(), "100")
                .isSameMovementAs(STORED))
        .isFalse();
    assertThat(identity(CUSTOMER, Operation.DEPOSIT, WALLET, "100.01").isSameMovementAs(STORED))
        .isFalse();
  }

  @Test
  void requiresEveryField() {
    Money amount = Money.of(BigDecimal.TEN, Money.THB);
    assertThatThrownBy(() -> new MovementIdentity(null, Operation.DEPOSIT, WALLET, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MovementIdentity(CUSTOMER, null, WALLET, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MovementIdentity(CUSTOMER, Operation.DEPOSIT, null, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MovementIdentity(CUSTOMER, Operation.DEPOSIT, WALLET, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> STORED.isSameMovementAs(null))
        .isInstanceOf(NullPointerException.class);
  }
}
