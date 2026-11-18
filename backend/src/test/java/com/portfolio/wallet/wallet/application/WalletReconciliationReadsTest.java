package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.wallet.application.WalletReconciliationReads.CompletedMovement;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.ReservedUsage;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.WalletBalances;
import com.portfolio.wallet.wallet.persistence.WalletReconciliationRepository;
import com.portfolio.wallet.wallet.persistence.WalletReconciliationRepository.BalanceRow;
import com.portfolio.wallet.wallet.persistence.WalletReconciliationRepository.MovementRow;
import com.portfolio.wallet.wallet.persistence.WalletReconciliationRepository.ReservedUsageRow;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The wallet module's reconciliation reads (documentations/06 MVP-ADR-03): mapping, propagation.
 */
class WalletReconciliationReadsTest {

  private final WalletReconciliationRepository repository =
      mock(WalletReconciliationRepository.class);
  private final WalletReconciliationReads reads = new WalletReconciliationReads(repository);

  @Test
  void rowsAreMappedOneToOne() {
    UUID wallet = UUID.randomUUID();
    UUID movement = UUID.randomUUID();
    LocalDate day = LocalDate.of(2026, 11, 14);
    when(repository.balances())
        .thenReturn(
            List.of(new BalanceRow(wallet, new BigDecimal("1.00"), new BigDecimal("2.00"))));
    when(repository.completedDeposits())
        .thenReturn(List.of(new MovementRow(movement, wallet, "DEP-1", BigDecimal.TEN)));
    when(repository.completedWithdrawals())
        .thenReturn(List.of(new MovementRow(movement, wallet, "WDR-1", BigDecimal.ONE)));
    when(repository.reservedUsage())
        .thenReturn(List.of(new ReservedUsageRow(wallet, "TRANSFER", day, new BigDecimal("2.00"))));

    assertThat(reads.balances())
        .containsExactly(
            new WalletBalances(wallet, new BigDecimal("1.00"), new BigDecimal("2.00")));
    assertThat(reads.completedDeposits())
        .containsExactly(new CompletedMovement(movement, wallet, "DEP-1", BigDecimal.TEN));
    assertThat(reads.completedWithdrawals())
        .containsExactly(new CompletedMovement(movement, wallet, "WDR-1", BigDecimal.ONE));
    assertThat(reads.reservedUsage())
        .containsExactly(new ReservedUsage(wallet, "TRANSFER", day, new BigDecimal("2.00")));
  }

  @Test
  void everyReadJoinsTheCallersTransaction() {
    for (Method method : WalletReconciliationReads.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).as(method.getName()).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly()).isTrue();
      }
    }
  }
}
