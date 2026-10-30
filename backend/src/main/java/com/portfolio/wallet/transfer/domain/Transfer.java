package com.portfolio.wallet.transfer.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A peer-to-peer transfer and its state machine (documentations/06 MVP-ADR-03, MVP-ADR-24).
 *
 * <p>Created in one of three states:
 *
 * <ul>
 *   <li>{@link #completed}: risk allow or monitor; posted to the ledger in the same transaction;
 *   <li>{@link #held}: risk manual review; {@code PENDING_REVIEW} until {@code reviewExpiresAt};
 *   <li>{@link #blocked}: risk block; no balance change.
 * </ul>
 *
 * <p>Only {@code PENDING_REVIEW} changes, and only to:
 *
 * <ul>
 *   <li>{@code COMPLETED} by {@link #approve}, strictly before the deadline;
 *   <li>{@code REJECTED} by {@link #reject}, strictly before the deadline;
 *   <li>{@code CANCELLED} by {@link #cancel}, strictly before the deadline;
 *   <li>{@code EXPIRED} by {@link #expire}, at or after the deadline.
 * </ul>
 *
 * Anything else throws {@link InvalidTransferStateException}; a deadline that has passed throws its
 * subclass {@link TransferReviewExpiredException}, so the caller can expire the transfer in the
 * same transaction (MVP-ADR-24). Balances and limit usage are changed by the caller, not here. Not
 * thread-safe: the caller holds the transfer row lock.
 */
public final class Transfer {

  /** Maximum rejection reason category length (V4 {@code varchar(40)}). */
  public static final int MAX_REASON_CATEGORY_LENGTH = 40;

  private final TransferDetails details;
  private final Instant heldAt;
  private final Instant reviewExpiresAt;
  private final long version;
  private TransferStatus status;
  private Instant decidedAt;
  private String rejectionReasonCategory;

  private Transfer(
      TransferDetails details,
      TransferStatus status,
      Instant heldAt,
      Instant reviewExpiresAt,
      Instant decidedAt,
      String rejectionReasonCategory,
      long version) {
    this.details = Objects.requireNonNull(details, "details");
    this.status = Objects.requireNonNull(status, "status");
    if ((heldAt == null) != (reviewExpiresAt == null)) {
      throw new IllegalArgumentException("heldAt and reviewExpiresAt go together");
    }
    if (status == TransferStatus.PENDING_REVIEW && heldAt == null) {
      throw new IllegalArgumentException("a held transfer needs heldAt and reviewExpiresAt");
    }
    if (heldAt != null && !reviewExpiresAt.isAfter(heldAt)) {
      throw new IllegalArgumentException("reviewExpiresAt must be after heldAt");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    this.heldAt = heldAt;
    this.reviewExpiresAt = reviewExpiresAt;
    this.decidedAt = decidedAt;
    this.rejectionReasonCategory = rejectionReasonCategory;
    this.version = version;
  }

  /** Risk allow or monitor: completed at once; the caller posts the ledger in the same commit. */
  public static Transfer completed(TransferDetails details) {
    return new Transfer(details, TransferStatus.COMPLETED, null, null, null, null, 0);
  }

  /**
   * Risk manual review: held until {@code heldAt + reviewDuration} (MVP-ADR-24).
   *
   * @param reviewDuration from configuration; must be positive
   */
  public static Transfer held(TransferDetails details, Instant heldAt, Duration reviewDuration) {
    Objects.requireNonNull(heldAt, "heldAt");
    Objects.requireNonNull(reviewDuration, "reviewDuration");
    if (reviewDuration.isZero() || reviewDuration.isNegative()) {
      throw new IllegalArgumentException("reviewDuration must be positive");
    }
    return new Transfer(
        details, TransferStatus.PENDING_REVIEW, heldAt, heldAt.plus(reviewDuration), null, null, 0);
  }

  /** Risk block: the row is kept, no money moves (MVP-ADR-24). */
  public static Transfer blocked(TransferDetails details) {
    return new Transfer(details, TransferStatus.BLOCKED, null, null, null, null, 0);
  }

  /** Rebuilds a stored transfer (used by the persistence mapper). */
  public static Transfer restore(
      TransferDetails details,
      TransferStatus status,
      Instant heldAt,
      Instant reviewExpiresAt,
      Instant decidedAt,
      String rejectionReasonCategory,
      long version) {
    return new Transfer(
        details, status, heldAt, reviewExpiresAt, decidedAt, rejectionReasonCategory, version);
  }

  /** Reviewer approval: {@code PENDING_REVIEW} → {@code COMPLETED}, before the deadline. */
  public void approve(Instant now) {
    requireOpenReview(now);
    decide(TransferStatus.COMPLETED, now);
  }

  /**
   * Reviewer rejection, or an approval that found a frozen or closed wallet: {@code PENDING_REVIEW}
   * → {@code REJECTED}, before the deadline (MVP-ADR-24).
   *
   * @param reasonCategory generic category shown to the customer, never rule details
   */
  public void reject(Instant now, String reasonCategory) {
    Objects.requireNonNull(reasonCategory, "reasonCategory");
    if (reasonCategory.isBlank() || reasonCategory.length() > MAX_REASON_CATEGORY_LENGTH) {
      throw new IllegalArgumentException("reasonCategory must be 1 to 40 characters");
    }
    requireOpenReview(now);
    decide(TransferStatus.REJECTED, now);
    rejectionReasonCategory = reasonCategory;
  }

  /** Sender cancel: {@code PENDING_REVIEW} → {@code CANCELLED}, before the deadline. */
  public void cancel(Instant now) {
    requireOpenReview(now);
    decide(TransferStatus.CANCELLED, now);
  }

  /** Expiry: {@code PENDING_REVIEW} → {@code EXPIRED}, at or after the deadline. */
  public void expire(Instant now) {
    requirePendingReview(now);
    if (now.isBefore(reviewExpiresAt)) {
      throw new InvalidTransferStateException("transfer review has not expired yet");
    }
    decide(TransferStatus.EXPIRED, now);
  }

  /** Whether a held transfer's deadline has been reached at {@code now}. */
  public boolean isReviewExpired(Instant now) {
    Objects.requireNonNull(now, "now");
    return reviewExpiresAt != null && !now.isBefore(reviewExpiresAt);
  }

  /** {@code ledger_status = POSTED} exactly when {@code COMPLETED} (V4; MVP-ADR-03). */
  public boolean isLedgerPosted() {
    return status == TransferStatus.COMPLETED;
  }

  private void requireOpenReview(Instant now) {
    requirePendingReview(now);
    if (isReviewExpired(now)) {
      throw new TransferReviewExpiredException("transfer review has expired");
    }
  }

  private void requirePendingReview(Instant now) {
    Objects.requireNonNull(now, "now");
    if (status != TransferStatus.PENDING_REVIEW) {
      throw new InvalidTransferStateException("transfer is not pending review");
    }
  }

  private void decide(TransferStatus target, Instant now) {
    status = target;
    decidedAt = now;
  }

  public UUID id() {
    return details.id();
  }

  public TransferDetails details() {
    return details;
  }

  public TransferStatus status() {
    return status;
  }

  public Optional<Instant> heldAt() {
    return Optional.ofNullable(heldAt);
  }

  public Optional<Instant> reviewExpiresAt() {
    return Optional.ofNullable(reviewExpiresAt);
  }

  public Optional<Instant> decidedAt() {
    return Optional.ofNullable(decidedAt);
  }

  public Optional<String> rejectionReasonCategory() {
    return Optional.ofNullable(rejectionReasonCategory);
  }

  public long version() {
    return version;
  }

  @Override
  public String toString() {
    return "Transfer[id=" + details.id() + ", status=" + status + "]";
  }
}
