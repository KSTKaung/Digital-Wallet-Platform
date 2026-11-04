package com.portfolio.wallet.user.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A KYC application and its state machine (documentations/02 §2.2; doc 06 MVP-ADR-10 amendment):
 *
 * <ul>
 *   <li>{@code DRAFT}: created by the customer; {@link #edit} changes it; {@link #submit} sends it
 *       for review;
 *   <li>{@code PENDING_REVIEW}: {@link #approve} or {@link #reject} decide it;
 *   <li>{@code APPROVED} and {@code REJECTED} are final. After a rejection the customer may create
 *       a new application.
 * </ul>
 *
 * Anything else throws {@link InvalidKycStateException}. V8's update trigger enforces the same
 * transitions and sets the submission and decision times. Not thread-safe: the caller holds the row
 * lock.
 */
public final class KycApplication {

  /** The size of {@code usr.kyc_applications.rejection_reason}. */
  public static final int MAX_REJECTION_REASON_LENGTH = 500;

  private final UUID id;
  private final UUID customerId;
  private KycDetails details;
  private KycStatus status;
  private UUID reviewerId;
  private String rejectionReason;

  private KycApplication(
      UUID id,
      UUID customerId,
      KycDetails details,
      KycStatus status,
      UUID reviewerId,
      String rejectionReason) {
    this.id = Objects.requireNonNull(id, "id");
    this.customerId = Objects.requireNonNull(customerId, "customerId");
    this.details = Objects.requireNonNull(details, "details");
    this.status = Objects.requireNonNull(status, "status");
    this.reviewerId = reviewerId;
    this.rejectionReason = rejectionReason;
  }

  /** Rebuilds a stored application. */
  public static KycApplication restore(
      UUID id,
      UUID customerId,
      KycDetails details,
      KycStatus status,
      UUID reviewerId,
      String rejectionReason) {
    return new KycApplication(id, customerId, details, status, reviewerId, rejectionReason);
  }

  /** Replaces the content of a draft (doc 08 §4B.2 Q7). */
  public void edit(KycDetails newDetails) {
    requireStatus(KycStatus.DRAFT, "edited");
    details = Objects.requireNonNull(newDetails, "newDetails");
  }

  /** {@code DRAFT} to {@code PENDING_REVIEW}. */
  public void submit() {
    requireStatus(KycStatus.DRAFT, "submitted");
    status = KycStatus.PENDING_REVIEW;
  }

  /** {@code PENDING_REVIEW} to {@code APPROVED}; the reviewer may not be the applicant. */
  public void approve(UUID reviewer) {
    requireDecidable(reviewer);
    status = KycStatus.APPROVED;
    reviewerId = reviewer;
  }

  /**
   * {@code PENDING_REVIEW} to {@code REJECTED}. The customer sees the reason, so it may not repeat
   * the application's document reference (doc 08 §4B.2 Q13).
   */
  public void reject(UUID reviewer, String reason) {
    requireDecidable(reviewer);
    String checked = KycText.requireReason("reason", reason, MAX_REJECTION_REASON_LENGTH);
    if (details.documentReference().appearsIn(checked)) {
      throw new InvalidKycDetailsException("reason", "must not contain the document reference");
    }
    status = KycStatus.REJECTED;
    reviewerId = reviewer;
    rejectionReason = checked;
  }

  private void requireDecidable(UUID reviewer) {
    Objects.requireNonNull(reviewer, "reviewer");
    requireStatus(KycStatus.PENDING_REVIEW, "decided");
    if (reviewer.equals(customerId)) {
      throw new IllegalArgumentException("a reviewer cannot decide their own application");
    }
  }

  private void requireStatus(KycStatus required, String action) {
    if (status != required) {
      throw new InvalidKycStateException(
          "KYC application is " + status + " and cannot be " + action);
    }
  }

  public UUID id() {
    return id;
  }

  public UUID customerId() {
    return customerId;
  }

  public KycDetails details() {
    return details;
  }

  public KycStatus status() {
    return status;
  }

  public UUID reviewerId() {
    return reviewerId;
  }

  public String rejectionReason() {
    return rejectionReason;
  }

  @Override
  public String toString() {
    return "KycApplication[id=" + id + ", status=" + status + "]";
  }
}
