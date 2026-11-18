package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.risk.persistence.RiskReconciliationRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the scheduled reconciliation reads from the risk module: the outcome of every review row
 * (documentations/06 MVP-ADR-03 check 5; MVP-ADR-24 "Review outcomes", D2). Read-only, no lock.
 * Never the reviewer's text, actor or times. Assessments and investigations are not read, so those
 * without a transfer are not flagged (MVP-ADR-24 Q4; doc 08 §5 step 7).
 *
 * <p>Joins the caller's transaction ({@link Propagation#MANDATORY}), so review rows are read at the
 * same instant as the transfers they are compared with.
 */
@Service
public class RiskReconciliationReads {

  private final RiskReconciliationRepository repository;

  public RiskReconciliationReads(RiskReconciliationRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /**
   * A review row's outcome.
   *
   * @param action {@code APPROVE}, {@code REJECT}, {@code CANCEL} or {@code EXPIRE}
   * @param decidedBy {@code STAFF}, {@code CUSTOMER} or {@code SYSTEM}
   */
  public record ReviewOutcome(
      UUID reviewId, UUID transferId, UUID assessmentId, String action, String decidedBy) {

    public ReviewOutcome {
      Objects.requireNonNull(reviewId, "reviewId");
      Objects.requireNonNull(transferId, "transferId");
      Objects.requireNonNull(assessmentId, "assessmentId");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(decidedBy, "decidedBy");
    }
  }

  /** Every review row's outcome. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<ReviewOutcome> reviewOutcomes() {
    return repository.reviewOutcomes().stream()
        .map(
            row ->
                new ReviewOutcome(
                    row.id(), row.transferId(), row.assessmentId(), row.action(), row.decidedBy()))
        .toList();
  }
}
