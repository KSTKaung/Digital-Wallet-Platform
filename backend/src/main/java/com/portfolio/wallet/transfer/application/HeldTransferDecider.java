package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.risk.application.RiskReviewOutcome;
import com.portfolio.wallet.risk.application.RiskReviewService;
import com.portfolio.wallet.risk.application.WalletUnavailability;
import com.portfolio.wallet.transfer.application.TransferAudit.ReviewFacts;
import com.portfolio.wallet.transfer.domain.RejectionCategory;
import com.portfolio.wallet.transfer.domain.Transfer;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import com.portfolio.wallet.wallet.application.ApprovalOutcome;
import com.portfolio.wallet.wallet.application.LockedTransferWallets;
import com.portfolio.wallet.wallet.application.WalletTransferService;
import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decides a held transfer whose row the caller has locked: the one sequence shared by the customer
 * cancel (5a), the expiry job (step 6) and the staff review (5b) (documentations/06 MVP-ADR-24
 * "Approve", "Reject", "Concurrency", "Review outcomes" D2, "Audit of review outcomes"; doc 08
 * §4C.3, §4C.4 criteria 7, 8, 11, 14a, 15; §5 notes).
 *
 * <h2>Preconditions (the caller's)</h2>
 *
 * Inside one transaction that applied the D14 timeouts first and then locked the transfer row
 * {@code FOR UPDATE} in a statement of its own ({@link TransferRepository#lockSentBy}, {@link
 * TransferRepository#lockForUpdate} or {@link TransferRepository#lockHeldForReview}), and found it
 * {@code PENDING_REVIEW} under that lock; a caller that finds any other status answers {@code 409
 * INVALID_TRANSFER_STATE} without calling this, so no review row is written for an action that lost
 * (doc 08 §5, V11 review). A staff caller has also refused a reviewer who is a party.
 *
 * <h2>The sequence</h2>
 *
 * <ol>
 *   <li>both wallets {@code FOR UPDATE} in one statement ({@link WalletTransferService#lock}); the
 *       locked sender must be the transfer's sender (doc 08 §5, step 4 notes);
 *   <li>the database time, read <b>after</b> those locks: a wait on the wallets therefore counts
 *       toward the deadline here, as it does in the V4 guard, so a valid action is never turned
 *       into a guard refusal by its own lock wait;
 *   <li><b>at or after the deadline</b>, whatever was requested: the hold is released on the stored
 *       {@code limit_date} and the transfer {@code EXPIRED} (cases 5, 6, 7). A staff rejection's
 *       reason is then neither checked nor stored (doc 08 §5, step 3 and V11 notes), so a late
 *       rejection with an invalid reason expires the transfer instead of answering {@code 400};
 *   <li>before the deadline, the requested outcome:
 *       <ul>
 *         <li>cancel (case 4): release, {@code CANCELLED};
 *         <li>staff rejection (case 2): the reason is checked first ({@code 400 VALIDATION_ERROR},
 *             nothing written), then release, {@code REJECTED} with {@code REVIEW_DECLINED};
 *         <li>staff approval: {@link WalletTransferService#approve} on the stored day captures the
 *             hold, credits the recipient and posts the ledger (case 1, {@code COMPLETED}, {@code
 *             ledger_status = POSTED}), or, with a frozen or closed wallet, releases the hold (case
 *             3, {@code REJECTED} with {@code WALLET_UNAVAILABLE});
 *       </ul>
 *   <li>the status {@code UPDATE} with the {@code version} check. The V4 guard's {@code
 *       restrict_violation} (the deadline crossed between step 2 and this statement) is {@code 409
 *       INVALID_TRANSFER_STATE}, and the caller's transaction rolls back, the release, capture and
 *       ledger posting included (no savepoints, doc 08 §5);
 *   <li>the risk review row ({@link RiskReviewService}, the case's five facts), then the audit row
 *       with the same facts (actor {@code CUSTOMER}, {@code STAFF} or {@code SYSTEM}), then the
 *       emails of the new status ({@link TransferEmails}), all in the same transaction.
 * </ol>
 *
 * <p>The reviewer's reason goes only into the review row: never into the audit row, an email, a log
 * line or an exception message.
 */
@Component
class HeldTransferDecider {

  /** PostgreSQL's {@code restrict_violation}, raised by the V4 transfer guard. */
  static final String RESTRICT_VIOLATION = "23001";

  static final String NOT_PENDING = "Transfer is not pending review";
  static final String DEADLINE_PASSED = "REVIEW_DEADLINE_PASSED";

  private static final String STAFF_REJECTION_CATEGORY = "STAFF_REJECTION";

  /** What was requested, and by whom. */
  enum Request {
    /** The sender's cancel: review case 4, or case 7 at or after the deadline. */
    CUSTOMER_CANCEL("CANCEL", ActorType.CUSTOMER),
    /** The expiry job (step 6): review case 5; only at or after the deadline. */
    EXPIRY_JOB(null, ActorType.SYSTEM),
    /** A compliance officer's approval: case 1 or 3, or case 6 at or after the deadline. */
    STAFF_APPROVE("APPROVE", ActorType.STAFF),
    /** A compliance officer's rejection: case 2, or case 6 at or after the deadline. */
    STAFF_REJECT("REJECT", ActorType.STAFF);

    private final String requestedAction;
    private final ActorType actorType;

    Request(String requestedAction, ActorType actorType) {
      this.requestedAction = requestedAction;
      this.actorType = actorType;
    }
  }

  /**
   * The outcome: the row as stored after the status change, and whether the deadline had passed
   * (the status is then {@code EXPIRED}). A class, not a record, so its accessors stay
   * package-private (ArchUnit {@code applicationApisHidePersistence}).
   */
  static final class Decision {

    private final TransferRow after;
    private final boolean expired;

    Decision(TransferRow after, boolean expired) {
      this.after = Objects.requireNonNull(after, "after");
      this.expired = expired;
    }

    TransferRow after() {
      return after;
    }

    boolean expired() {
      return expired;
    }
  }

  private final TransferRepository transfers;
  private final WalletTransferService wallets;
  private final RiskReviewService reviews;
  private final AuditService audit;
  private final TransferEmails emails;

  HeldTransferDecider(
      TransferRepository transfers,
      WalletTransferService wallets,
      RiskReviewService reviews,
      AuditService audit,
      TransferEmails emails) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.emails = Objects.requireNonNull(emails, "emails");
  }

  /**
   * Runs the sequence of the class description on {@code held}, for any request but a staff
   * rejection, which carries a reason ({@link #decide(TransferRow, Request, UUID, String,
   * InetAddress)}).
   */
  Decision decide(TransferRow held, Request request, UUID actorId, InetAddress clientIp) {
    return decide(held, request, actorId, null, clientIp);
  }

  /**
   * Runs the sequence of the class description on {@code held}.
   *
   * @param actorId the triggering actor; null only for {@link Request#EXPIRY_JOB}
   * @param staffReason the reviewer's reason, for {@link Request#STAFF_REJECT} only (may be
   *     invalid: it is checked only before the deadline); null for every other request
   * @throws ApiException {@code INVALID_TRANSFER_STATE} if the V4 guard refused the change; {@code
   *     VALIDATION_ERROR} for an invalid reason of a staff rejection before the deadline
   * @throws IllegalStateException if a precondition does not hold
   */
  Decision decide(
      TransferRow held, Request request, UUID actorId, String staffReason, InetAddress clientIp) {
    Objects.requireNonNull(held, "held");
    Objects.requireNonNull(request, "request");
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("a held transfer is decided under its row lock");
    }
    if (!TransferStatus.PENDING_REVIEW.name().equals(held.status())) {
      throw new IllegalStateException("only a transfer found held under its lock is decided");
    }
    if ((actorId == null) != (request == Request.EXPIRY_JOB)) {
      throw new IllegalStateException("only the expiry job acts without an actor");
    }
    if (staffReason != null && request != Request.STAFF_REJECT) {
      throw new IllegalStateException("only a staff rejection carries a reason");
    }
    LockedTransferWallets locked = wallets.lock(held.senderWalletId(), held.receiverWalletId());
    if (!locked.sender().walletId().equals(held.senderWalletId())
        || !locked.sender().customerId().equals(held.senderCustomerId())) {
      throw new IllegalStateException("locked sender wallet is not the transfer's sender");
    }
    // After the wallet locks, so their wait counts toward the deadline as in the V4 guard.
    Instant now = transfers.databaseNow();
    Transfer transfer = TransferViews.toDomain(held);
    Money amount = transfer.details().amount();
    if (transfer.isReviewExpired(now)) {
      // Cases 5, 6, 7: whatever was requested; a staff reason is dropped unchecked (D2).
      wallets.release(locked, amount, held.limitDate());
      transfer.expire(now);
      TransferRow after = update(held, transfer, null);
      record(held, expiredOutcome(request, actorId));
      finish(held, after, request, actorId, expiredFacts(request), null, clientIp);
      return new Decision(after, true);
    }
    return switch (request) {
      case EXPIRY_JOB ->
          throw new IllegalStateException(
              "the expiry job decides only transfers past their deadline");
      case CUSTOMER_CANCEL -> {
        wallets.release(locked, amount, held.limitDate());
        transfer.cancel(now);
        TransferRow after = update(held, transfer, null);
        ReviewFacts facts = new ReviewFacts("CANCEL", "CUSTOMER", "CANCEL", null, null);
        record(held, RiskReviewOutcome.customerCancel(actorId));
        finish(held, after, request, actorId, facts, null, clientIp);
        yield new Decision(after, false);
      }
      case STAFF_REJECT -> {
        // Checked before anything is written; a 400 rolls back the locks alone.
        RiskReviewOutcome outcome = RiskReviewOutcome.staffRejection(actorId, staffReason);
        wallets.release(locked, amount, held.limitDate());
        transfer.reject(now, RejectionCategory.REVIEW_DECLINED);
        TransferRow after = update(held, transfer, null);
        ReviewFacts facts =
            new ReviewFacts("REJECT", "STAFF", "REJECT", STAFF_REJECTION_CATEGORY, null);
        record(held, outcome);
        finish(held, after, request, actorId, facts, null, clientIp);
        yield new Decision(after, false);
      }
      case STAFF_APPROVE -> approve(held, transfer, locked, now, actorId, clientIp);
    };
  }

  /**
   * Cases 1 and 3: the wallet module captures the hold, or releases it for a wallet unavailable.
   */
  private Decision approve(
      TransferRow held,
      Transfer transfer,
      LockedTransferWallets locked,
      Instant now,
      UUID reviewer,
      InetAddress clientIp) {
    ApprovalOutcome outcome =
        wallets.approve(
            locked, transfer.details().amount(), held.limitDate(), transfer.details().reference());
    return switch (outcome) {
      case ApprovalOutcome.Captured captured -> {
        transfer.approve(now);
        TransferRow after = update(held, transfer, TransferService.POSTED);
        ReviewFacts facts = new ReviewFacts("APPROVE", "STAFF", "APPROVE", null, null);
        record(held, RiskReviewOutcome.staffApproval(reviewer));
        finish(
            held,
            after,
            Request.STAFF_APPROVE,
            reviewer,
            facts,
            captured.settlement().ledgerTransactionId(),
            clientIp);
        yield new Decision(after, false);
      }
      case ApprovalOutcome.ReleasedWalletUnavailable released -> {
        transfer.reject(now, RejectionCategory.WALLET_UNAVAILABLE);
        TransferRow after = update(held, transfer, null);
        WalletUnavailability wallet = UnavailableWallets.toRisk(released.wallet());
        ReviewFacts facts = new ReviewFacts("REJECT", "SYSTEM", "APPROVE", null, wallet.name());
        record(held, RiskReviewOutcome.systemRejection(reviewer, wallet));
        finish(held, after, Request.STAFF_APPROVE, reviewer, facts, null, clientIp);
        yield new Decision(after, false);
      }
    };
  }

  private static RiskReviewOutcome expiredOutcome(Request request, UUID actorId) {
    return switch (request) {
      case CUSTOMER_CANCEL -> RiskReviewOutcome.expiredOnLateCancel(actorId);
      case EXPIRY_JOB -> RiskReviewOutcome.expiredByJob();
      case STAFF_APPROVE -> RiskReviewOutcome.expiredOnLateApproval(actorId);
      case STAFF_REJECT -> RiskReviewOutcome.expiredOnLateRejection(actorId);
    };
  }

  private static ReviewFacts expiredFacts(Request request) {
    return new ReviewFacts("EXPIRE", "SYSTEM", request.requestedAction, null, DEADLINE_PASSED);
  }

  /** The review row, in this transaction (RiskReviewService joins it). */
  private void record(TransferRow held, RiskReviewOutcome outcome) {
    reviews.record(held.id(), held.riskAssessmentId(), outcome);
  }

  /** The audit row with the review's facts, then the emails, last, so all commit together. */
  private void finish(
      TransferRow held,
      TransferRow after,
      Request request,
      UUID actorId,
      ReviewFacts facts,
      UUID ledgerTransactionId,
      InetAddress clientIp) {
    audit.record(
        TransferAudit.reviewOutcome(
            held, after, actorId, request.actorType, facts, ledgerTransactionId, clientIp));
    // Q9: the emails of the new status, last, so they commit or roll back with everything above.
    emails.statusChanged(after);
  }

  /** The status {@code UPDATE}; the guard's {@code restrict_violation} is {@code 409}. */
  private TransferRow update(TransferRow held, Transfer decided, String ledgerStatus) {
    String category = decided.rejectionReasonCategory().map(RejectionCategory::name).orElse(null);
    try {
      return transfers
          .updateDecision(
              held.id(), decided.status().name(), category, ledgerStatus, held.version())
          .orElseThrow(
              () -> new IllegalStateException("transfer version changed under its row lock"));
    } catch (DataAccessException e) {
      if (isRestrictViolation(e)) {
        throw new ApiException(ErrorCode.INVALID_TRANSFER_STATE, NOT_PENDING);
      }
      throw e;
    }
  }

  /** Whether {@code failure} is PostgreSQL's {@code restrict_violation}, anywhere in its causes. */
  static boolean isRestrictViolation(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && RESTRICT_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
