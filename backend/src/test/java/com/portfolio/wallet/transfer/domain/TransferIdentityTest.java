package com.portfolio.wallet.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Q14 applied to transfers (documentations/08 §4B.2 Q14; owner, 2026-11-11, option a): sender,
 * sender wallet, recipient wallet, amount and currency all match.
 */
class TransferIdentityTest {

  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID SENDER_WALLET = UUID.randomUUID();
  private static final UUID RECEIVER_WALLET = UUID.randomUUID();

  private static TransferIdentity identity(
      UUID sender, UUID senderWallet, UUID receiverWallet, String amount) {
    return new TransferIdentity(
        sender, senderWallet, receiverWallet, Money.of(new BigDecimal(amount), Money.THB));
  }

  private static final TransferIdentity STORED =
      identity(SENDER, SENDER_WALLET, RECEIVER_WALLET, "100.00");

  @Test
  void sameTransferWhenEveryFieldMatches() {
    assertThat(identity(SENDER, SENDER_WALLET, RECEIVER_WALLET, "100").isSameTransferAs(STORED))
        .isTrue();
  }

  @Test
  void anyDifferenceIsAnotherTransfer() {
    assertThat(
            identity(UUID.randomUUID(), SENDER_WALLET, RECEIVER_WALLET, "100.00")
                .isSameTransferAs(STORED))
        .isFalse();
    assertThat(
            identity(SENDER, UUID.randomUUID(), RECEIVER_WALLET, "100.00").isSameTransferAs(STORED))
        .isFalse();
    assertThat(
            identity(SENDER, SENDER_WALLET, UUID.randomUUID(), "100.00").isSameTransferAs(STORED))
        .isFalse();
    assertThat(identity(SENDER, SENDER_WALLET, RECEIVER_WALLET, "100.01").isSameTransferAs(STORED))
        .isFalse();
  }

  @Test
  void requiresEveryFieldAndPrintsNothing() {
    Money amount = Money.of(BigDecimal.TEN, Money.THB);
    assertThatThrownBy(() -> new TransferIdentity(null, SENDER_WALLET, RECEIVER_WALLET, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TransferIdentity(SENDER, null, RECEIVER_WALLET, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TransferIdentity(SENDER, SENDER_WALLET, null, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TransferIdentity(SENDER, SENDER_WALLET, RECEIVER_WALLET, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> STORED.isSameTransferAs(null))
        .isInstanceOf(NullPointerException.class);
    assertThat(STORED.toString())
        .doesNotContain(SENDER.toString())
        .doesNotContain(RECEIVER_WALLET.toString())
        .doesNotContain("100");
  }
}
