package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.risk.application.WalletUnavailability;
import com.portfolio.wallet.wallet.application.UnavailableWallet;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The wallet module's {@link UnavailableWallet} maps name by name onto the risk module's {@link
 * WalletUnavailability} (and so onto the system reason the review row stores; 5a review N6): {@code
 * SENDER_FROZEN} to {@code SENDER_WALLET_FROZEN} and so on, every value once.
 */
class UnavailableWalletsTest {

  @ParameterizedTest
  @EnumSource(UnavailableWallet.class)
  void eachWalletValueMapsToTheSameNamedSystemReason(UnavailableWallet wallet) {
    String[] parts = wallet.name().split("_", 2);
    String expected = parts[0] + "_WALLET_" + parts[1];

    assertThat(UnavailableWallets.toRisk(wallet).name()).isEqualTo(expected);
  }

  @Test
  void theMappingIsABijection() {
    Set<WalletUnavailability> mapped =
        Arrays.stream(UnavailableWallet.values())
            .map(UnavailableWallets::toRisk)
            .collect(Collectors.toSet());

    assertThat(mapped).containsExactlyInAnyOrder(WalletUnavailability.values());
    assertThat(UnavailableWallet.values()).hasSameSizeAs(WalletUnavailability.values());
  }

  @Test
  void aWalletIsRequired() {
    assertThatThrownBy(() -> UnavailableWallets.toRisk(null))
        .isInstanceOf(NullPointerException.class);
  }
}
