package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The customer's own wallet reads (documentations/02 §2.3; doc 06 MVP-ADR-10, -18, -21). */
class WalletQueryServiceTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final Instant CREATED = Instant.parse("2026-11-03T08:00:00Z");

  private final WalletRepository repository = mock(WalletRepository.class);
  private final WalletQueryService service =
      new WalletQueryService(repository, LimitPropertiesTest.adrValues().toPolicy());

  private void stored(String tier, String status, String available, String reserved) {
    when(repository.findByCustomer(CUSTOMER))
        .thenReturn(
            Optional.of(
                new WalletRow(
                    UUID.randomUUID(),
                    "12345678903",
                    CUSTOMER,
                    tier,
                    status,
                    "THB",
                    new BigDecimal(available),
                    new BigDecimal(reserved),
                    3,
                    CREATED,
                    CREATED)));
  }

  @Test
  void theWalletWithBalancesAtScaleTwoAndNoInternalId() {
    stored("BASIC", "ACTIVE", "1250.5", "0");

    WalletView view = service.wallet(CUSTOMER);

    assertThat(view.walletNumber()).isEqualTo("12345678903");
    assertThat(view.status()).isEqualTo("ACTIVE");
    assertThat(view.tier()).isEqualTo("BASIC");
    assertThat(view.availableBalance()).isEqualTo(new BigDecimal("1250.50"));
    assertThat(view.reservedBalance()).isEqualTo(new BigDecimal("0.00"));
    assertThat(view.currency()).isEqualTo("THB");
    assertThat(view.createdAt()).isEqualTo(CREATED);
    assertThat(view.toString()).doesNotContain("12345678903", "1250");
  }

  @Test
  void theBalance() {
    stored("BASIC", "FROZEN", "10.00", "2.50");

    WalletBalanceView balance = service.balance(CUSTOMER);

    assertThat(balance)
        .isEqualTo(new WalletBalanceView(new BigDecimal("10.00"), new BigDecimal("2.50"), "THB"));
    assertThat(balance.toString()).doesNotContain("10.00");
  }

  @Test
  void theStatus() {
    stored("VERIFIED", "FROZEN", "0", "0");

    assertThat(service.status(CUSTOMER)).isEqualTo(new WalletStatusView("FROZEN"));
  }

  @Test
  void limitsOfABasicWallet() {
    stored("BASIC", "ACTIVE", "0", "0");

    assertThat(service.limits(CUSTOMER))
        .isEqualTo(
            new WalletLimitsView(
                new BigDecimal("100000.00"),
                new BigDecimal("50000.00"),
                new BigDecimal("200000.00"),
                new BigDecimal("50000.00"),
                new BigDecimal("30000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("1.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                "THB"));
  }

  @Test
  void aVerifiedWalletHasTheHigherDailyTransferLimitOnly() {
    stored("VERIFIED", "ACTIVE", "0", "0");

    WalletLimitsView limits = service.limits(CUSTOMER);

    assertThat(limits.dailyTransfer()).isEqualTo(new BigDecimal("500000.00"));
    assertThat(limits.singleTransfer()).isEqualTo(new BigDecimal("50000.00"));
    assertThat(limits.dailyWithdrawal()).isEqualTo(new BigDecimal("50000.00"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"wallet", "balance", "status", "limits"})
  void withoutAWalletEveryReadIsWalletNotFound(String read) {
    when(repository.findByCustomer(CUSTOMER)).thenReturn(Optional.empty());
    Function<UUID, Object> call =
        switch (read) {
          case "wallet" -> service::wallet;
          case "balance" -> service::balance;
          case "status" -> service::status;
          default -> service::limits;
        };

    assertThatThrownBy(() -> call.apply(CUSTOMER))
        .isInstanceOf(ApiException.class)
        .hasMessage(WalletQueryService.NOT_FOUND)
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.WALLET_NOT_FOUND);
  }
}
