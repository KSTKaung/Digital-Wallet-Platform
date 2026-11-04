package com.portfolio.wallet.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.user.domain.VerificationLevel;
import com.portfolio.wallet.wallet.application.CustomerAlreadyHasWalletException;
import com.portfolio.wallet.wallet.application.OpenedWallet;
import com.portfolio.wallet.wallet.application.WalletService;
import java.net.InetAddress;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The user module's seam over {@code wallet.application}. */
class WalletModuleCustomerWalletsTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID REVIEWER = UUID.randomUUID();

  private final WalletService walletService = mock(WalletService.class);
  private final WalletModuleCustomerWallets wallets =
      new WalletModuleCustomerWallets(walletService);

  @Test
  void existsReflectsTheWalletModule() {
    when(walletService.existsForCustomer(CUSTOMER)).thenReturn(true);

    assertThat(wallets.exists(CUSTOMER)).isTrue();
    assertThat(wallets.exists(UUID.randomUUID())).isFalse();
  }

  @ParameterizedTest
  @EnumSource(VerificationLevel.class)
  void openPassesTheLevelByNameAndReturnsTheWalletId(VerificationLevel level) throws Exception {
    InetAddress ip = InetAddress.getByName("192.0.2.1");
    UUID wallet = UUID.randomUUID();
    when(walletService.openForApprovedKyc(CUSTOMER, level.name(), REVIEWER, ip))
        .thenReturn(new OpenedWallet(wallet, UUID.randomUUID()));

    assertThat(wallets.open(CUSTOMER, level, REVIEWER, ip)).isEqualTo(wallet);
  }

  @Test
  void anExistingWalletIsTheApprovalsConflict() {
    when(walletService.openForApprovedKyc(CUSTOMER, "BASIC", REVIEWER, null))
        .thenThrow(new CustomerAlreadyHasWalletException());

    assertThatThrownBy(() -> wallets.open(CUSTOMER, VerificationLevel.BASIC, REVIEWER, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_KYC_STATE))
        .hasMessage(WalletModuleCustomerWallets.ALREADY_HAS_WALLET);
  }
}
