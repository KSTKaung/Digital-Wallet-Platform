package com.portfolio.wallet.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.ledger.domain.InvalidLedgerAccountStatusTransitionException;
import com.portfolio.wallet.ledger.persistence.LedgerAccountRepository;
import com.portfolio.wallet.ledger.persistence.LedgerAccountRow;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The customer ledger account of a wallet (documentations/01 §6.3; doc 07 §4.1): creation, and the
 * status change that goes with its wallet's freeze (doc 06 MVP-ADR-10 amendment).
 */
class LedgerAccountServiceTest {

  private final LedgerAccountRepository repository = mock(LedgerAccountRepository.class);
  private final LedgerAccountService service = new LedgerAccountService(repository);

  @Test
  void opensAnActiveThbCustomerAccountOwnedByTheWallet() {
    UUID wallet = UUID.randomUUID();

    UUID account = service.openCustomerAccount(wallet);

    assertThat(account).isNotNull().isNotEqualTo(wallet);
    verify(repository).insertCustomerAccount(account, wallet, "THB", "ACTIVE");
  }

  @Test
  void eachAccountGetsItsOwnId() {
    assertThat(service.openCustomerAccount(UUID.randomUUID()))
        .isNotEqualTo(service.openCustomerAccount(UUID.randomUUID()));
  }

  @Test
  void aWalletIdIsRequired() {
    assertThatThrownBy(() -> service.openCustomerAccount(null))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(repository);
  }

  // Status ----------------------------------------------------------------------------------------

  private UUID stored(UUID wallet, String status) {
    UUID id = UUID.randomUUID();
    when(repository.findCustomerAccountForUpdate(wallet))
        .thenReturn(Optional.of(new LedgerAccountRow(id, null, "CUSTOMER", wallet, "THB", status)));
    when(repository.updateStatus(any(), anyString(), anyString())).thenReturn(1);
    return id;
  }

  @Test
  void freezeLocksTheAccountThenMovesItFromActiveToFrozen() {
    UUID wallet = UUID.randomUUID();
    UUID account = stored(wallet, "ACTIVE");

    service.freezeCustomerAccount(wallet);

    InOrder order = inOrder(repository);
    order.verify(repository).findCustomerAccountForUpdate(wallet);
    order.verify(repository).updateStatus(account, "ACTIVE", "FROZEN");
  }

  @Test
  void unfreezeMovesAFrozenAccountBackToActive() {
    UUID wallet = UUID.randomUUID();
    UUID account = stored(wallet, "FROZEN");

    service.unfreezeCustomerAccount(wallet);

    verify(repository).updateStatus(account, "FROZEN", "ACTIVE");
  }

  @Test
  void anAccountThatDriftedFromItsWalletIsRefusedAndNotWritten() {
    UUID wallet = UUID.randomUUID();
    stored(wallet, "FROZEN");
    assertThatThrownBy(() -> service.freezeCustomerAccount(wallet))
        .isInstanceOf(InvalidLedgerAccountStatusTransitionException.class);

    UUID closed = UUID.randomUUID();
    stored(closed, "CLOSED");
    assertThatThrownBy(() -> service.unfreezeCustomerAccount(closed))
        .isInstanceOf(InvalidLedgerAccountStatusTransitionException.class);
    verify(repository, never()).updateStatus(any(), anyString(), anyString());
  }

  @Test
  void aWalletWithoutAnAccountIsAnInternalError() {
    UUID wallet = UUID.randomUUID();
    when(repository.findCustomerAccountForUpdate(wallet)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.freezeCustomerAccount(wallet))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aStatusThatMovedUnderTheLockIsAnInternalError() {
    UUID wallet = UUID.randomUUID();
    stored(wallet, "ACTIVE");
    when(repository.updateStatus(any(), anyString(), anyString())).thenReturn(0);

    assertThatThrownBy(() -> service.freezeCustomerAccount(wallet))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ledger account status changed under its row lock");
  }
}
