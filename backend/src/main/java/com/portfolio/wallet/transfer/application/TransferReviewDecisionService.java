package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.transfer.application.HeldTransferDecider.Decision;
import com.portfolio.wallet.transfer.application.HeldTransferDecider.Request;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A compliance officer approves or rejects a held transfer (documentations/02 §2.7; doc 06
 * MVP-ADR-12, MVP-ADR-24 "Review", "Concurrency", "Dependencies", review cases 1, 2, 3 and 6; doc
 * 08 §4C.3, §4C.4 criteria 11, 14a, 15; §5 notes). Called by the {@code admin} module, which serves
 * the endpoints; the reviewer is the JWT subject, whose role {@code SecurityConfig} and the
 * controller have checked.
 *
 * <h2>One transaction of its own, in the lock order of every review action</h2>
 *
 * <ol>
 *   <li>the D14 timeouts ({@link TransactionTimeouts#apply()}), first;
 *   <li>the transfer row {@code FOR UPDATE}, in a statement of its own, only if it was held for
 *       review ({@link TransferRepository#lockHeldForReview}): a missing transfer and one that
 *       never went to review are the identical {@code 404} and lock nothing;
 *   <li>the reviewer may be neither the sender nor the recipient (MVP-ADR-24, compared by the auth
 *       user id, which is the customer id): {@code 403 FORBIDDEN}, nothing written, as for a KYC
 *       reviewer's own application. Staff and customer accounts are separate (MVP-ADR-12), so this
 *       is a second guard;
 *   <li>under the lock the status must still be {@code PENDING_REVIEW}; otherwise {@code 409
 *       INVALID_TRANSFER_STATE} and nothing is written: an action that lost the row lock to a
 *       cancel, the expiry job or another reviewer writes no review row, audit row or email (doc 08
 *       §5, V11 notes);
 *   <li>{@link HeldTransferDecider}: both wallets locked, the deadline checked with the database
 *       time after those locks, then the outcome, the status {@code UPDATE}, the review row, the
 *       audit row and the emails.
 * </ol>
 *
 * A late action (case 6) commits the expiry and then answers {@code 409}; so, as the cancel, it
 * always opens a transaction of its own and refuses to run inside a caller's (an explicit guard).
 *
 * <p><b>Module design (MVP-ADR-24 "Dependencies").</b> The ADR has {@code admin} run the review in
 * one transaction, calling {@code transfer} for the row lock and then {@code risk} for the review
 * row. PR 5a placed the review-row recording inside the transfer module's shared sequence, which
 * the cancel and the expiry job use; the staff review uses the same sequence, so the transaction is
 * opened here and {@code admin} calls this service. The intent is kept: one transaction, the
 * transfer row locked first, the review row recorded by {@code risk} inside it, and {@code risk}
 * never calls {@code transfer}.
 *
 * <p>No {@code Idempotency-Key}: a review action is a state transition of an existing transfer, not
 * a new money movement (doc 02 §1.3), and counts only toward the general rate limit (Q15). A
 * repeated action finds the transfer decided and gets {@code 409}.
 *
 * <p>The reviewer's reason is never logged, audited or returned to a customer: it reaches only the
 * staff-only {@code risk.risk_reviews.reason}.
 */
@Service
public class TransferReviewDecisionService {

  static final String PARTY_TO_TRANSFER = "A reviewer cannot decide a transfer they are a party to";

  private final TransferRepository transfers;
  private final HeldTransferDecider decider;
  private final TransactionTimeouts timeouts;
  private final TransactionTemplate transactions;

  TransferReviewDecisionService(
      TransferRepository transfers,
      HeldTransferDecider decider,
      TransactionTimeouts timeouts,
      PlatformTransactionManager transactionManager) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.decider = Objects.requireNonNull(decider, "decider");
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    this.transactions = new TransactionTemplate(transactionManager);
    // Each statement sees what the previous holder of the row lock committed.
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Approves the held transfer: {@code COMPLETED} with the ledger posted (case 1), or {@code
   * REJECTED} with {@code WALLET_UNAVAILABLE} and the hold released if a wallet is frozen or closed
   * (case 3). Returns after the commit.
   *
   * @throws ApiException the identical {@code NOT_FOUND}; {@code FORBIDDEN} for a reviewer who is a
   *     party; {@code INVALID_TRANSFER_STATE} if it is no longer held, or if the deadline had
   *     passed (it is then {@code EXPIRED}, committed, case 6)
   * @throws IllegalTransactionStateException inside a transaction
   */
  public void approve(UUID reviewerId, UUID transferId, InetAddress clientIp) {
    decide(reviewerId, transferId, Request.STAFF_APPROVE, null, clientIp);
  }

  /**
   * Rejects the held transfer with the reviewer's reason (case 2): {@code REJECTED} with {@code
   * REVIEW_DECLINED}, the hold and the limit released. The deadline is checked before the reason,
   * so a late rejection expires the transfer (case 6) and drops the reason, valid or not.
   *
   * @param reason checked by the risk module's reason rules (MVP-ADR-10 text rules, at most 500
   *     characters) only before the deadline
   * @throws ApiException as {@link #approve}, and {@code VALIDATION_ERROR} naming {@code reason}
   *     (never its value) for an invalid reason before the deadline, with nothing written
   */
  public void reject(UUID reviewerId, UUID transferId, String reason, InetAddress clientIp) {
    decide(reviewerId, transferId, Request.STAFF_REJECT, reason == null ? "" : reason, clientIp);
  }

  private void decide(
      UUID reviewerId, UUID transferId, Request request, String reason, InetAddress clientIp) {
    Objects.requireNonNull(reviewerId, "reviewerId");
    Objects.requireNonNull(transferId, "transferId");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "a transfer review runs in a transaction of its own");
    }
    Boolean expired =
        transactions.execute(
            status -> {
              timeouts.apply();
              TransferRow row =
                  transfers.lockHeldForReview(transferId).orElseThrow(ApiException::notFound);
              if (reviewerId.equals(row.senderCustomerId())
                  || reviewerId.equals(row.receiverCustomerId())) {
                throw new ApiException(ErrorCode.FORBIDDEN, PARTY_TO_TRANSFER);
              }
              if (!TransferStatus.PENDING_REVIEW.name().equals(row.status())) {
                throw new ApiException(
                    ErrorCode.INVALID_TRANSFER_STATE, HeldTransferDecider.NOT_PENDING);
              }
              Decision decision = decider.decide(row, request, reviewerId, reason, clientIp);
              return decision.expired();
            });
    // After the commit, so the expiry of a late action stays committed.
    if (Boolean.TRUE.equals(expired)) {
      throw new ApiException(ErrorCode.INVALID_TRANSFER_STATE, HeldTransferDecider.NOT_PENDING);
    }
  }
}
