package com.portfolio.wallet.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.ledger.application.LedgerReconciliationReads.AccountTotals;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads.Posting;
import com.portfolio.wallet.ledger.persistence.LedgerReconciliationRepository;
import com.portfolio.wallet.ledger.persistence.LedgerReconciliationRepository.AccountTotalsRow;
import com.portfolio.wallet.ledger.persistence.LedgerReconciliationRepository.PostingRow;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The ledger's reconciliation reads (documentations/06 MVP-ADR-03): mapping and propagation. */
class LedgerReconciliationReadsTest {

  private final LedgerReconciliationRepository repository =
      mock(LedgerReconciliationRepository.class);
  private final LedgerReconciliationReads reads = new LedgerReconciliationReads(repository);

  @Test
  void onlyCustomerAccountsNameAWalletAndTheBalanceIsCreditsMinusDebits() {
    UUID customer = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    UUID system = UUID.randomUUID();
    when(repository.accountTotals())
        .thenReturn(
            List.of(
                new AccountTotalsRow(
                    customer,
                    "CUSTOMER",
                    wallet,
                    null,
                    new BigDecimal("2.00"),
                    new BigDecimal("7.50")),
                new AccountTotalsRow(
                    system,
                    "SYSTEM",
                    null,
                    "SYS-CASH-CLEARING",
                    new BigDecimal("7.50"),
                    new BigDecimal("2.00")),
                new AccountTotalsRow(
                    UUID.randomUUID(),
                    "SUSPENSE",
                    null,
                    "SYS-SUSPENSE",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO),
                // Not a system account, whatever its code says.
                new AccountTotalsRow(
                    UUID.randomUUID(),
                    "SUSPENSE",
                    null,
                    "SYS-CASH-CLEARING",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO)));

    List<AccountTotals> totals = reads.accountTotals();

    assertThat(totals.get(0).walletId()).isEqualTo(wallet);
    assertThat(totals.get(0).balance()).isEqualByComparingTo("5.50");
    assertThat(totals.get(1).walletId()).isNull();
    assertThat(totals.get(1).balance()).isEqualByComparingTo("-5.50");
    // Only the SYSTEM account coded SYS-CASH-CLEARING is the cash clearing account.
    assertThat(totals)
        .extracting(AccountTotals::cashClearing)
        .containsExactly(false, true, false, false);
  }

  @Test
  void postingsCarryTheirEntryTotalsAndAccounts() {
    UUID id = UUID.randomUUID();
    UUID debit = UUID.randomUUID();
    UUID credit = UUID.randomUUID();
    when(repository.postings())
        .thenReturn(
            List.of(
                new PostingRow(
                    id,
                    "DEP-1",
                    "DEPOSIT",
                    2,
                    new BigDecimal("4.00"),
                    new BigDecimal("4.0"),
                    List.of(debit),
                    List.of(credit))));

    List<Posting> postings = reads.postings();

    assertThat(postings)
        .containsExactly(
            new Posting(
                id,
                "DEP-1",
                "DEPOSIT",
                2,
                new BigDecimal("4.00"),
                new BigDecimal("4.0"),
                Set.of(debit),
                Set.of(credit)));
    assertThat(postings.getFirst().balanced()).isTrue();
  }

  @Test
  void everyReadJoinsTheCallersTransaction() {
    for (Method method : LedgerReconciliationReads.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).as(method.getName()).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly()).isTrue();
      }
    }
  }
}
