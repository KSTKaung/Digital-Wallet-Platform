package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.risk.application.RiskReviewOutcome;
import com.portfolio.wallet.risk.application.RiskReviewService;
import com.portfolio.wallet.transfer.domain.Transfer;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
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
 * "Concurrency", "Review outcomes" D2, "Audit of review outcomes"; doc 08 §4C.3, §4C.4 criteria 7,
 * 8, 11, 14a; §5 notes).
 *
 * <h2>Preconditions (the caller's)</h2>
 *
 * Inside one transaction that applied the D14 timeouts first and then locked the transfer row
 * {@code FOR UPDATE} in a statement of its own ({@link TransferRepository#lockSentBy} or {@link
 * TransferRepository#lockForUpdate}), and found it {@code PENDING_REVIEW} under that lock; a caller
 * that finds any other status answers {@code 409 INVALID_TRANSFER_STATE} without calling this, so
 * no review row is written for an action that lost (doc 08 §5, V11 review).
 *
 * <h2>The sequence</h2>
 *
 * <ol>
 *   <li>both wallets {@code FOR UPDATE} in one statement ({@link WalletTransferService#lock}); the
 *       locked sender must be the transfer's sender (doc 08 §5, step 4 notes);
 *   <li>the database time, read <b>after</b> those locks: a wait on the wallets therefore counts
 *       toward the deadline here, as it does in the V4 guard, so a valid action is never turned
 *       into a guard refusal by its own lock wait;
 *   <li>the hold is released on the transfer's <b>stored</b> {@code limit_date}, in any wallet
 *       status (MVP-ADR-22, -24); 5b's approval captures it here instead;
 *   <li>the status {@code UPDATE} with the {@code version} check: the requested outcome before the
 *       deadline, {@code EXPIRED} at or after it. The V4 guard's {@code restrict_violation} (the
 *       deadline crossed between step 2 and this statement) is {@code 409 INVALID_TRANSFER_STATE},
 *       and the caller's transaction rolls back, the release included;
 *   <li>the risk review row ({@link RiskReviewService}), then the audit row with the same five
 *       facts, in the same transaction.
 * </ol>
 */
@Component
class HeldTransferDecider {

  /** PostgreSQL's {@code restrict_violation}, raised by the V4 transfer guard. */
  static final String RESTRICT_VIOLATION = "23001";

  static final String NOT_PENDING = "Transfer is not pending review";
  static final String DEADLINE_PASSED = "REVIEW_DEADLINE_PASSED";

  /** What was requested, and by whom; 5b adds the staff approval and rejection. */
  enum Request {
    /** The sender's cancel: review case 4, or case 7 at or after the deadline. */
    CUSTOMER_CANCEL,
    /** The expiry job (step 6): review case 5; only at or after the deadline. */
    EXPIRY_JOB
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

  HeldTransferDecider(
      TransferRepository transfers,
      WalletTransferService wallets,
      RiskReviewService reviews,
      AuditService audit) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  /**
   * Runs the sequence of the class description on {@code held}.
   *
   * @param actorId the triggering actor; null only for {@link Request#EXPIRY_JOB}
   * @throws ApiException {@code INVALID_TRANSFER_STATE} if the V4 guard refused the change
   * @throws IllegalStateException if a precondition does not hold
   */
  Decision decide(TransferRow held, Request request, UUID actorId, InetAddress clientIp) {
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
    LockedTransferWallets locked = wallets.lock(held.senderWalletId(), held.receiverWalletId());
    if (!locked.sender().walletId().equals(held.senderWalletId())
        || !locked.sender().customerId().equals(held.senderCustomerId())) {
      throw new IllegalStateException("locked sender wallet is not the transfer's sender");
    }
    // After the wallet locks, so their wait counts toward the deadline as in the V4 guard.
    Instant now = transfers.databaseNow();
    Transfer transfer = TransferViews.toDomain(held);
    boolean expired = transfer.isReviewExpired(now);
    if (!expired && request == Request.EXPIRY_JOB) {
      throw new IllegalStateException("the expiry job decides only transfers past their deadline");
    }

    wallets.release(locked, transfer.details().amount(), held.limitDate());
    if (expired) {
      transfer.expire(now);
    } else {
      transfer.cancel(now);
    }
    TransferRow after = update(held, transfer.status());
    reviews.record(held.id(), held.riskAssessmentId(), outcome(request, actorId, expired));
    audit.record(auditEntry(held, after, request, actorId, expired, clientIp));
    return new Decision(after, expired);
  }

  private static RiskReviewOutcome outcome(Request request, UUID actorId, boolean expired) {
    return switch (request) {
      case CUSTOMER_CANCEL ->
          expired
              ? RiskReviewOutcome.expiredOnLateCancel(actorId)
              : RiskReviewOutcome.customerCancel(actorId);
      case EXPIRY_JOB -> RiskReviewOutcome.expiredByJob();
    };
  }

  private static AuditEntry auditEntry(
      TransferRow before,
      TransferRow after,
      Request request,
      UUID actorId,
      boolean expired,
      InetAddress clientIp) {
    String requested = request == Request.CUSTOMER_CANCEL ? "CANCEL" : null;
    ActorType actorType = request == Request.EXPIRY_JOB ? ActorType.SYSTEM : ActorType.CUSTOMER;
    return expired
        ? TransferAudit.reviewOutcome(
            before,
            after,
            actorId,
            actorType,
            "EXPIRE",
            "SYSTEM",
            requested,
            DEADLINE_PASSED,
            clientIp)
        : TransferAudit.reviewOutcome(
            before, after, actorId, actorType, "CANCEL", "CUSTOMER", requested, null, clientIp);
  }

  /** The status {@code UPDATE}; the guard's {@code restrict_violation} is {@code 409}. */
  private TransferRow update(TransferRow held, TransferStatus status) {
    try {
      return transfers
          .updateDecision(held.id(), status.name(), null, null, held.version())
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
