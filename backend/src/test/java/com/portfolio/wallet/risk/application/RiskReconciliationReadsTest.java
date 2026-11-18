package com.portfolio.wallet.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.risk.application.RiskReconciliationReads.ReviewOutcome;
import com.portfolio.wallet.risk.persistence.RiskReconciliationRepository;
import com.portfolio.wallet.risk.persistence.RiskReconciliationRepository.ReviewOutcomeRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The risk module's reconciliation read (documentations/06 MVP-ADR-03 check 5). */
class RiskReconciliationReadsTest {

  private final RiskReconciliationRepository repository = mock(RiskReconciliationRepository.class);
  private final RiskReconciliationReads reads = new RiskReconciliationReads(repository);

  @Test
  void reviewRowsAreMappedOneToOneAndJoinTheCallersTransaction() throws Exception {
    UUID review = UUID.randomUUID();
    UUID transfer = UUID.randomUUID();
    UUID assessment = UUID.randomUUID();
    when(repository.reviewOutcomes())
        .thenReturn(
            List.of(new ReviewOutcomeRow(review, transfer, assessment, "EXPIRE", "SYSTEM")));

    assertThat(reads.reviewOutcomes())
        .containsExactly(new ReviewOutcome(review, transfer, assessment, "EXPIRE", "SYSTEM"));
    Transactional transactional =
        RiskReconciliationReads.class
            .getMethod("reviewOutcomes")
            .getAnnotation(Transactional.class);
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(transactional.readOnly()).isTrue();
  }
}
