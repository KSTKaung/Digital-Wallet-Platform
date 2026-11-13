package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.transfer.application.HeldTransferDecider.Decision;
import com.portfolio.wallet.transfer.application.HeldTransferDecider.Request;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import com.portfolio.wallet.wallet.application.TransferCounterparties;
import com.portfolio.wallet.wallet.application.WalletLookupView;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /api/v1/transfers/{id}/cancel}: the sender cancels a held transfer (documentations/02
 * §2.4; doc 06 MVP-ADR-19, -22, -24 incl. "Review outcomes" cases 4 and 7; doc 08 §4C.3, §4C.4
 * criteria 7, 11, 14a; §5 notes).
 *
 * <h2>One transaction of its own, in the lock order of every review action</h2>
 *
 * <ol>
 *   <li>the D14 timeouts ({@link TransactionTimeouts#apply()}), first;
 *   <li>the transfer row {@code FOR UPDATE}, in a statement of its own that also requires the
 *       caller to be the sender ({@link TransferRepository#lockSentBy}): a missing transfer and
 *       another customer's give the identical {@code 404} (MVP-ADR-19) and lock nothing;
 *   <li>under that lock the status must still be {@code PENDING_REVIEW}; otherwise {@code 409
 *       INVALID_TRANSFER_STATE} and nothing is written, no review row included (an action that lost
 *       the row lock to another cancel or the expiry job, doc 08 §5 V11 notes);
 *   <li>{@link HeldTransferDecider}: both wallets locked, then the database time read after those
 *       locks, the hold released on the stored {@code limit_date} in any wallet status, the status
 *       {@code UPDATE} ({@code CANCELLED} before the deadline, case 4; {@code EXPIRED} at or after
 *       it, case 7), the review row and the audit row.
 * </ol>
 *
 * A late cancel commits the expiry and then answers {@code 409}. So the cancel always opens a
 * transaction of its own and refuses to run inside a caller's transaction (an explicit guard,
 * {@link IllegalTransactionStateException}): joined to an outer transaction, the 409 could roll the
 * expiry back with it, or the outer transaction could hold locks taken before the transfer row.
 * ({@code PROPAGATION_NEVER} would not do: it runs the callback with no transaction at all.)
 *
 * <p>The transfer row lock, the status check under it and the single transaction make each hold
 * released exactly once: a second cancel, or the expiry job, waits for the lock (the job skips it)
 * and then finds the transfer no longer {@code PENDING_REVIEW} (Q4 invariant).
 *
 * <p>No {@code Idempotency-Key}: doc 02 requires it only on the money POSTs that create a movement
 * (§1.3), and the cancel counts only toward the general rate limit (Q15). A repeated cancel finds
 * the transfer {@code CANCELLED} and gets {@code 409}.
 */
@Service
public class TransferCancellationService {

  static final String NOT_PENDING = HeldTransferDecider.NOT_PENDING;

  private final TransferRepository transfers;
  private final HeldTransferDecider decider;
  private final TransferCounterparties counterparties;
  private final TransactionTimeouts timeouts;
  private final TransactionTemplate transactions;

  TransferCancellationService(
      TransferRepository transfers,
      HeldTransferDecider decider,
      TransferCounterparties counterparties,
      TransactionTimeouts timeouts,
      PlatformTransactionManager transactionManager) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.decider = Objects.requireNonNull(decider, "decider");
    this.counterparties = Objects.requireNonNull(counterparties, "counterparties");
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    this.transactions = new TransactionTemplate(transactionManager);
    // Each statement sees what the previous holder of the row lock committed.
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Cancels the caller's held transfer, as described on the class.
   *
   * @return the cancelled transfer, built after the commit
   * @throws ApiException the identical {@code NOT_FOUND}, or {@code INVALID_TRANSFER_STATE} (not
   *     held any more, or the deadline passed: the transfer is then {@code EXPIRED})
   * @throws IllegalTransactionStateException inside a transaction
   */
  public TransferView cancel(UUID callerId, UUID transferId, InetAddress clientIp) {
    Objects.requireNonNull(callerId, "callerId");
    Objects.requireNonNull(transferId, "transferId");
    // M2: its own transaction only; the expiry of a late cancel commits before its 409.
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "a transfer cancel runs in a transaction of its own");
    }
    Outcome outcome =
        Objects.requireNonNull(
            transactions.execute(status -> cancelInTransaction(callerId, transferId, clientIp)));
    TransferRow row = outcome.result();
    // After the commit: no name is looked up under the money locks.
    UUID counterpartyWallet = TransferViews.counterpartyWallet(row, callerId);
    WalletLookupView counterparty =
        counterparties.of(List.of(counterpartyWallet)).get(counterpartyWallet);
    return TransferViews.view(
        row,
        callerId,
        new CounterpartyView(counterparty.maskedName(), counterparty.maskedWalletNumber()));
  }

  private Outcome cancelInTransaction(UUID callerId, UUID transferId, InetAddress clientIp) {
    timeouts.apply();
    TransferRow row =
        transfers.lockSentBy(transferId, callerId).orElseThrow(ApiException::notFound);
    if (!TransferStatus.PENDING_REVIEW.name().equals(row.status())) {
      throw new ApiException(ErrorCode.INVALID_TRANSFER_STATE, NOT_PENDING);
    }
    Decision decision = decider.decide(row, Request.CUSTOMER_CANCEL, callerId, clientIp);
    return decision.expired() ? Outcome.EXPIRED : Outcome.cancelled(decision.after());
  }

  /**
   * Turned into a result or an exception only after the commit, so an expiry stays committed. A
   * class, not a record: its accessor stays private to this service (ArchUnit {@code
   * applicationApisHidePersistence}).
   */
  private static final class Outcome {

    static final Outcome EXPIRED = new Outcome(null);

    private final TransferRow cancelled;

    private Outcome(TransferRow cancelled) {
      this.cancelled = cancelled;
    }

    static Outcome cancelled(TransferRow row) {
      return new Outcome(Objects.requireNonNull(row, "row"));
    }

    TransferRow result() {
      if (cancelled == null) {
        throw new ApiException(ErrorCode.INVALID_TRANSFER_STATE, NOT_PENDING);
      }
      return cancelled;
    }
  }
}
