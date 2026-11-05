package com.portfolio.wallet.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.ledger.domain.LedgerAccountNotActiveException;
import com.portfolio.wallet.ledger.persistence.LedgerAccountRow;
import com.portfolio.wallet.ledger.persistence.LedgerPostingRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Posting templates of documentations/01 §6.4 as written to the ledger tables. */
class LedgerPostingServiceTest {

  private static final UUID WALLET = UUID.randomUUID();
  private static final UUID CUSTOMER_ACCOUNT = UUID.randomUUID();
  private static final UUID CLEARING = UUID.randomUUID();
  private static final Money AMOUNT = Money.of(new BigDecimal("250.50"), Money.THB);

  private final LedgerPostingRepository postings = mock(LedgerPostingRepository.class);
  private final LedgerPostingService service = new LedgerPostingService(postings);

  private static LedgerAccountRow customer(String status) {
    return new LedgerAccountRow(CUSTOMER_ACCOUNT, null, "CUSTOMER", WALLET, "THB", status);
  }

  @BeforeEach
  void setUp() {
    when(postings.findCustomerAccount(WALLET)).thenReturn(Optional.of(customer("ACTIVE")));
    when(postings.findByCode("SYS-CASH-CLEARING"))
        .thenReturn(
            Optional.of(
                new LedgerAccountRow(
                    CLEARING, "SYS-CASH-CLEARING", "SYSTEM", null, "THB", "ACTIVE")));
  }

  @Test
  void depositDebitsCashClearingAndCreditsTheCustomer() {
    UUID id = service.postDeposit(WALLET, "DEP-1", AMOUNT);

    InOrder order = inOrder(postings);
    order.verify(postings).insertTransaction(eq(id), eq("DEP-1"), eq("DEPOSIT"), eq("THB"), any());
    order.verify(postings).insertEntry(id, CLEARING, "DEBIT", new BigDecimal("250.50"), "THB");
    order
        .verify(postings)
        .insertEntry(id, CUSTOMER_ACCOUNT, "CREDIT", new BigDecimal("250.50"), "THB");
  }

  @Test
  void withdrawalDebitsTheCustomerAndCreditsCashClearing() {
    UUID id = service.postWithdrawal(WALLET, "WDR-1", AMOUNT);

    InOrder order = inOrder(postings);
    order
        .verify(postings)
        .insertTransaction(eq(id), eq("WDR-1"), eq("WITHDRAWAL"), eq("THB"), any());
    order
        .verify(postings)
        .insertEntry(id, CUSTOMER_ACCOUNT, "DEBIT", new BigDecimal("250.50"), "THB");
    order.verify(postings).insertEntry(id, CLEARING, "CREDIT", new BigDecimal("250.50"), "THB");
  }

  @Test
  void aFrozenCustomerAccountIsRefusedBeforeAnythingIsWritten() {
    when(postings.findCustomerAccount(WALLET)).thenReturn(Optional.of(customer("FROZEN")));

    assertThatThrownBy(() -> service.postWithdrawal(WALLET, "WDR-2", AMOUNT))
        .isInstanceOf(LedgerAccountNotActiveException.class);
    verify(postings, never())
        .insertTransaction(any(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void missingAccountsAreInternalErrors() {
    when(postings.findCustomerAccount(WALLET)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.postDeposit(WALLET, "DEP-2", AMOUNT))
        .isInstanceOf(IllegalStateException.class);

    when(postings.findCustomerAccount(WALLET)).thenReturn(Optional.of(customer("ACTIVE")));
    when(postings.findByCode("SYS-CASH-CLEARING")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.postDeposit(WALLET, "DEP-3", AMOUNT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.postDeposit(null, "DEP-4", AMOUNT))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void returnsTheNewTransactionId() {
    assertThat(service.postDeposit(WALLET, "DEP-5", AMOUNT)).isNotNull();
  }
}
