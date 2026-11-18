package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.transfer.application.TransferReconciliationReads.CompletedTransfer;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads.HeldSum;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads.HeldTransferOutcome;
import com.portfolio.wallet.transfer.persistence.TransferReconciliationRepository;
import com.portfolio.wallet.transfer.persistence.TransferReconciliationRepository.CompletedRow;
import com.portfolio.wallet.transfer.persistence.TransferReconciliationRepository.HeldOutcomeRow;
import com.portfolio.wallet.transfer.persistence.TransferReconciliationRepository.HeldSumRow;
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
 * The transfer module's reconciliation reads (documentations/06 MVP-ADR-03): mapping, propagation.
 */
class TransferReconciliationReadsTest {

  private final TransferReconciliationRepository repository =
      mock(TransferReconciliationRepository.class);
  private final TransferReconciliationReads reads = new TransferReconciliationReads(repository);

  @Test
  void rowsAreMappedOneToOne() {
    UUID id = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("13.00");
    LocalDate day = LocalDate.of(2026, 11, 14);
    when(repository.completed())
        .thenReturn(List.of(new CompletedRow(id, "TRF-1", amount, id, other)));
    when(repository.heldSums()).thenReturn(List.of(new HeldSumRow(id, other, day, amount)));
    when(repository.everHeld())
        .thenReturn(List.of(new HeldOutcomeRow(id, "REJECTED", "REVIEW_DECLINED", other)));

    assertThat(reads.completed())
        .containsExactly(new CompletedTransfer(id, "TRF-1", amount, id, other));
    assertThat(reads.heldSums()).containsExactly(new HeldSum(id, other, day, amount));
    assertThat(reads.everHeld())
        .containsExactly(new HeldTransferOutcome(id, "REJECTED", "REVIEW_DECLINED", other));
  }

  @Test
  void everyReadJoinsTheCallersTransaction() {
    for (Method method : TransferReconciliationReads.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).as(method.getName()).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly()).isTrue();
      }
    }
  }
}
