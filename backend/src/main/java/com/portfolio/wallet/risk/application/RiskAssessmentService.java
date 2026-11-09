package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.risk.domain.RiskDecision;
import com.portfolio.wallet.risk.domain.RiskEvaluation;
import com.portfolio.wallet.risk.domain.RiskFacts;
import com.portfolio.wallet.risk.domain.RiskPolicy;
import com.portfolio.wallet.risk.domain.RuleHit;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository.WindowCount;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The risk assessment of a transfer (documentations/06 MVP-ADR-24; doc 08 §4C.2 Q3 step 6, Q4, Q13;
 * §4C.4 criteria 3, 4, 20). The public API the {@code transfer} module calls before its money
 * transaction.
 *
 * <h2>One transaction of its own</h2>
 *
 * {@link #assess} opens its own transaction and refuses to run inside one: the assessment is Q3
 * step 6, committed before the money transaction (step 7) starts, and it never holds the sender
 * lock while money locks are taken (doc 07 §8). In that transaction, in this order:
 *
 * <ol>
 *   <li>the <b>transaction preparer</b> runs (no-op today; PR 5a step 4 wires the D14 {@code SET
 *       LOCAL lock_timeout} and {@code statement_timeout} into it, so they bound the lock wait);
 *   <li>the sender's {@code risk.sender_risk_state} row is created if absent and locked, as the
 *       first statement that touches data: concurrent assessments of one sender run one after
 *       another, so none can miss another's assessment in the frequency count;
 *   <li>the frequency count (the sender's assessments in the window, plus the current one, every
 *       decision included, orphan assessments too, Q4) and the database time, read together;
 *   <li>the policy scores the facts;
 *   <li>the assessment, one rule-hit row per triggered rule and, for {@code BLOCK}, an {@code OPEN}
 *       investigation (Q13) are inserted, so a {@code BLOCK} assessment never exists without its
 *       investigation;
 *   <li>one <b>audit row</b> of the risk decision ({@code AuditService.record}, which joins this
 *       transaction): the decision, the assessment id, the sender as actor and whether an
 *       investigation was opened; never the amount, score, rule codes or weights (MVP-ADR-24 "Audit
 *       of risk decisions", owner 2026-11-09). The assessment, its hits, the investigation and the
 *       audit row commit or roll back together, which also covers assessments that end without a
 *       transfer (Q4) and a commit whose reply was lost. The audit insert takes no money lock; then
 *       the transaction commits.
 * </ol>
 *
 * <h2>Time</h2>
 *
 * Every time is database time: {@code assessed_at} is the transaction's {@code now()} (V5 trigger),
 * the window is computed in SQL against the same {@code now()}, and the sender wallet's age is that
 * {@code now()} minus the wallet's {@code created_at}, itself database time (V3). The application
 * {@code Clock} is not used. {@code now()} is the transaction start, before the sender-lock wait
 * and the insert, so at the window's edge a later assessment sees this one for the window minus
 * that wait: a skew bounded by the lock wait ({@code lock_timeout} once D14 lands in step 4), an
 * accepted residual (see {@link RiskAssessmentRepository}).
 *
 * <h2>Failure (criterion 4)</h2>
 *
 * Any failure in the transaction (the lock, a statement or lock timeout, the count, the evaluation,
 * an insert, the audit row) rolls the whole assessment back, so no assessment, hit, investigation
 * or audit row is left, and is reported as {@code ApiException(SYSTEM_ERROR)}: a failed evaluation
 * never counts as score 0. The one exception is a commit whose outcome is unknown (the connection
 * lost at {@code COMMIT}): the assessment may then exist, as an orphan the frequency rule counts
 * (Q4). The caller, the transfer service of PR 5a step 5, then writes the audit row of the failure
 * (independently, because this transaction has rolled back) and creates no transfer row and no
 * hold; that is not done here, because risk never depends on transfer. A transfer therefore has two
 * audit rows: this risk decision and, from the transfer module, the transfer outcome.
 *
 * <h2>Orphans (Q4)</h2>
 *
 * A committed assessment stays even if the money transaction then refuses the transfer under its
 * locks or loses to a request with the same {@code Idempotency-Key}; a {@code BLOCK} assessment
 * keeps its investigation as well. Such assessments count in the frequency rule, and reconciliation
 * does not flag them.
 *
 * <h2>Logs</h2>
 *
 * Nothing here logs a score, decision, rule code, weight, amount or id. A failure is logged at
 * {@code ERROR} with its exception only, whose messages carry none of them.
 */
@Service
public class RiskAssessmentService {

  private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

  /** The message of the {@code SYSTEM_ERROR}; it says nothing about the rules. */
  static final String FAILURE_MESSAGE = "An unexpected error occurred";

  private final RiskAssessmentRepository assessments;
  private final RiskPolicy policy;
  private final long windowSeconds;
  private final TransactionTemplate transactions;
  private final Runnable transactionPreparer;
  private final AuditService audit;

  @Autowired
  public RiskAssessmentService(
      RiskAssessmentRepository assessments,
      RiskPolicy policy,
      RiskProperties properties,
      AuditService audit,
      PlatformTransactionManager transactionManager) {
    // No per-transaction settings yet: PR 5a step 4 sets the D14 lock and statement timeouts here
    // (doc 06 "Lock and statement timeouts"; doc 08 §4C.2 Q6).
    this(
        assessments,
        policy,
        properties.frequencyWindowSeconds(),
        audit,
        transactionManager,
        () -> {});
  }

  /**
   * @param transactionPreparer runs as the first action of each assessment transaction, before the
   *     sender lock: the place for {@code SET LOCAL lock_timeout} and {@code statement_timeout}. If
   *     it throws, the assessment fails like any other failure ({@code SYSTEM_ERROR})
   */
  RiskAssessmentService(
      RiskAssessmentRepository assessments,
      RiskPolicy policy,
      long windowSeconds,
      AuditService audit,
      PlatformTransactionManager transactionManager,
      Runnable transactionPreparer) {
    this.audit = Objects.requireNonNull(audit, "audit");
    this.assessments = Objects.requireNonNull(assessments, "assessments");
    this.policy = Objects.requireNonNull(policy, "policy");
    if (windowSeconds < 1) {
      throw new IllegalArgumentException("the frequency window must be at least 1 second");
    }
    this.windowSeconds = windowSeconds;
    this.transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    // Pinned: each statement must see what the previous holder of the sender lock committed.
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.transactionPreparer = Objects.requireNonNull(transactionPreparer, "transactionPreparer");
  }

  /**
   * Assesses one transfer in a transaction of its own and commits the assessment, as described on
   * the class.
   *
   * @return the committed assessment's id and decision; the score stays in the risk schema
   * @throws ApiException {@code SYSTEM_ERROR} if the assessment failed; nothing was stored, unless
   *     the commit outcome is unknown (the connection was lost at {@code COMMIT}), in which case
   *     the assessment may exist as an orphan (Q4)
   * @throws IllegalStateException if called inside a transaction (a programming error)
   */
  public RiskAssessmentResult assess(RiskAssessmentRequest request) {
    Objects.requireNonNull(request, "request");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "a risk assessment runs in its own transaction, before the money transaction");
    }
    try {
      return Objects.requireNonNull(transactions.execute(status -> assessInTransaction(request)));
    } catch (RuntimeException e) {
      log.error("Risk assessment failed; it is rolled back and the transfer is refused", e);
      throw new ApiException(ErrorCode.SYSTEM_ERROR, FAILURE_MESSAGE);
    }
  }

  private RiskAssessmentResult assessInTransaction(RiskAssessmentRequest request) {
    transactionPreparer.run();
    assessments.lockSender(request.senderCustomerId());
    WindowCount window = assessments.countInWindow(request.senderCustomerId(), windowSeconds);
    RiskFacts facts =
        new RiskFacts(
            request.amount(),
            // The current transfer counts too (MVP-ADR-24).
            Math.addExact(window.assessments(), 1),
            request.completedTransferToRecipientExists(),
            Duration.between(request.senderWalletCreatedAt(), window.databaseNow()));
    RiskEvaluation evaluation = policy.evaluate(facts);
    UUID assessmentId =
        assessments.insertAssessment(
            request.transferId(),
            request.senderCustomerId(),
            request.amount().amount(),
            request.amount().currency().getCurrencyCode(),
            evaluation.score(),
            evaluation.decision().name());
    for (RuleHit hit : evaluation.hits()) {
      assessments.insertRuleHit(assessmentId, hit.rule().code(), hit.weight());
    }
    boolean investigationOpened = evaluation.decision() == RiskDecision.BLOCK;
    if (investigationOpened) {
      assessments.openInvestigation(assessmentId);
    }
    AssessmentDecision decision = AssessmentDecision.of(evaluation.decision());
    // Last, in this transaction: the audit row commits or rolls back with what it records.
    audit.record(
        RiskAudit.decided(
            request.senderCustomerId(),
            request.clientIp(),
            assessmentId,
            decision,
            investigationOpened));
    return new RiskAssessmentResult(assessmentId, decision);
  }
}
