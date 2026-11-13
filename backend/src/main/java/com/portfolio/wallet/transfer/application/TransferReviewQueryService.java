package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transfer side of the staff review reads (documentations/02 §2.7; doc 06 MVP-ADR-24 amendment
 * "Review queue (Q12)"; doc 08 §4C.2 Q12). Called by the {@code admin} module, which adds the risk
 * score and rules, the legal names and the masked wallet numbers.
 *
 * <ul>
 *   <li>{@link #queue}: {@code PENDING_REVIEW} transfers only, soonest deadline first, then by id,
 *       paged 1–100; the count and the page are read in one read-only {@code REPEATABLE READ}
 *       snapshot, so {@code totalElements} matches the items;
 *   <li>{@link #get}: any transfer that was held for review, whatever its status now, so the detail
 *       returned after a decision shows its outcome. A missing id and a transfer that never went to
 *       review are the identical {@code 404}.
 * </ul>
 *
 * Read-only; no lock is taken.
 */
@Service
public class TransferReviewQueryService {

  /** doc 02 §1.5: size 1–100. */
  public static final int MAX_PAGE_SIZE = TransferQueryService.MAX_PAGE_SIZE;

  private final TransferRepository transfers;
  private final TransactionTemplate snapshot;

  public TransferReviewQueryService(
      TransferRepository transfers, PlatformTransactionManager transactionManager) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.snapshot = new TransactionTemplate(transactionManager);
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /**
   * @param page from 0
   * @param size 1–100
   * @throws ApiException {@code VALIDATION_ERROR} for a page or size out of range
   */
  public HeldTransferPage queue(int page, int size) {
    if (page < 0) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, TransferQueryService.INVALID_PAGE);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, TransferQueryService.INVALID_SIZE);
    }
    long offset = (long) page * size;
    return Objects.requireNonNull(
        snapshot.execute(
            status -> {
              long total = transfers.countAwaitingReview();
              List<HeldTransferView> items =
                  transfers.pageAwaitingReview(size, offset).stream()
                      .map(TransferReviewQueryService::view)
                      .toList();
              return new HeldTransferPage(items, page, size, total, (total + size - 1) / size);
            }));
  }

  /**
   * @throws ApiException the identical {@code NOT_FOUND} for a missing id or a transfer that was
   *     never held for review
   */
  public HeldTransferView get(UUID transferId) {
    Objects.requireNonNull(transferId, "transferId");
    return transfers
        .findHeldForReview(transferId)
        .map(TransferReviewQueryService::view)
        .orElseThrow(ApiException::notFound);
  }

  static HeldTransferView view(TransferRow row) {
    if (row.heldAt() == null) {
      throw new IllegalStateException("only a transfer held for review has a staff view");
    }
    return new HeldTransferView(
        row.id(),
        row.reference(),
        row.status(),
        row.amount().setScale(Money.SCALE),
        row.currency(),
        row.senderCustomerId(),
        row.receiverCustomerId(),
        row.senderWalletId(),
        row.receiverWalletId(),
        row.riskAssessmentId(),
        row.createdAt(),
        row.heldAt(),
        row.reviewExpiresAt(),
        row.decidedAt(),
        row.rejectionReasonCategory(),
        row.ledgerStatus());
  }
}
