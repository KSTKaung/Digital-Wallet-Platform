package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.risk.domain.ReviewOutcome;
import com.portfolio.wallet.risk.persistence.RiskReviewRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Records the outcome of a held transfer in {@code risk.risk_reviews} (documentations/06 MVP-ADR-24
 * "Concurrency", "Dependencies" and amendment "Review outcomes", D2; doc 08 §4C.4 criterion 14a).
 *
 * <p>It must run <b>inside the caller's transaction</b> ({@link Propagation#MANDATORY}): approve,
 * reject, cancel and expire each run in one transaction that first locks the transfer row ({@code
 * FOR UPDATE}, in the {@code transfer} module), changes the status, releases or captures the hold,
 * writes the audit row, and calls this to write the review row. So the review row, the status
 * change and the audit row commit or roll back together, and no review row exists for an action
 * that lost. Risk never calls transfer.
 *
 * <p>Nothing here is logged; the reviewer's reason is staff-only.
 */
@Service
public class RiskReviewService {

  private final RiskReviewRepository reviews;
  private final Runnable markRollbackOnly;

  @Autowired
  public RiskReviewService(RiskReviewRepository reviews) {
    this(reviews, () -> TransactionAspectSupport.currentTransactionStatus().setRollbackOnly());
  }

  /**
   * @param markRollbackOnly marks the caller's transaction rollback-only; tests replace it
   */
  RiskReviewService(RiskReviewRepository reviews, Runnable markRollbackOnly) {
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.markRollbackOnly = Objects.requireNonNull(markRollbackOnly, "markRollbackOnly");
  }

  /**
   * Inserts the review row of {@code transferId}.
   *
   * @param transferId the held transfer
   * @param assessmentId its assessment; the database checks that the assessment is the transfer's
   *     (V5 {@code fk_risk_reviews_assessment})
   * @throws ReviewAlreadyRecordedException if the transfer already has a review row. The caller's
   *     transaction is marked rollback-only first, so a caller that catches this and returns
   *     normally gets {@link org.springframework.transaction.UnexpectedRollbackException} at commit
   *     instead of losing its work silently (PostgreSQL has aborted the transaction anyway)
   * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
   *     active
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID transferId, UUID assessmentId, RiskReviewOutcome outcome) {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(assessmentId, "assessmentId");
    ReviewOutcome review = Objects.requireNonNull(outcome, "outcome").toDomain();
    try {
      reviews.insert(
          transferId,
          assessmentId,
          review.action().name(),
          review.decidedBy().name(),
          review.requestedAction().map(Enum::name).orElse(null),
          review.actorUserId().orElse(null),
          review.reasonCategory().map(Enum::name).orElse(null),
          review.reason().orElse(null),
          review.systemReason().map(Enum::name).orElse(null));
    } catch (DuplicateKeyException e) {
      // uq_risk_reviews_transfer: the only unique key besides the generated id. Explicit, so the
      // outcome does not depend on the exception's rollback rules.
      markRollbackOnly.run();
      throw new ReviewAlreadyRecordedException();
    }
  }
}
