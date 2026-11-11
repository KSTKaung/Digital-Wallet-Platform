package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link TransferCounterparties}: the lookup's masking, any wallet status (MVP-ADR-20; Q8). */
class TransferCounterpartiesTest {

  private final WalletRepository wallets = mock(WalletRepository.class);
  private final RecipientNames names = mock(RecipientNames.class);
  private final TransferCounterparties counterparties = new TransferCounterparties(wallets, names);

  private static WalletRow wallet(UUID id, UUID customer, String number, String status) {
    return new WalletRow(
        id,
        number,
        customer,
        "BASIC",
        status,
        "THB",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  @Test
  void eachWalletIsMaskedLikeTheLookupWhateverItsStatus() {
    UUID active = UUID.randomUUID();
    UUID frozen = UUID.randomUUID();
    UUID customer = UUID.randomUUID();
    UUID nameless = UUID.randomUUID();
    when(wallets.findByIds(List.of(active, frozen)))
        .thenReturn(
            List.of(
                wallet(active, customer, "12345678901", "ACTIVE"),
                wallet(frozen, nameless, "10987654321", "FROZEN")));
    when(names.maskedLegalName(customer)).thenReturn(Optional.of("Somchai J."));
    when(names.maskedLegalName(nameless)).thenReturn(Optional.empty());

    Map<UUID, WalletLookupView> views = counterparties.of(List.of(active, frozen));

    assertThat(views.get(active).maskedName()).isEqualTo("Somchai J.");
    assertThat(views.get(active).maskedWalletNumber()).isEqualTo("*******8901");
    assertThat(views.get(frozen).maskedName()).isNull();
    assertThat(views.get(frozen).maskedWalletNumber()).isEqualTo("*******4321");
    verify(names, times(1)).maskedLegalName(customer);
  }

  @Test
  void noIdsReadNothingAndAMissingWalletIsAnError() {
    assertThat(counterparties.of(List.of())).isEmpty();
    verifyNoInteractions(wallets, names);

    UUID missing = UUID.randomUUID();
    when(wallets.findByIds(List.of(missing))).thenReturn(List.of());
    assertThatThrownBy(() -> counterparties.of(List.of(missing)))
        .isInstanceOf(IllegalStateException.class);
  }
}
