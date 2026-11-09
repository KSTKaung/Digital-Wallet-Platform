package com.portfolio.wallet.risk.domain;

import com.portfolio.wallet.common.text.InvalidTextException;
import com.portfolio.wallet.common.text.TextRules;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One review row's content: the actual outcome, the decision source, the requested action, the
 * triggering actor, and the staff category with the reviewer's text or the system reason
 * (documentations/06 MVP-ADR-24 amendment "Review outcomes", owner 2026-11-06, D2 and S1).
 *
 * <p>It can only be built by the seven factory methods, one per case of the D2 table, so a
 * combination outside them cannot exist; V11 {@code ck_risk_reviews_outcome} refuses one in the
 * database as well.
 *
 * <table>
 *   <caption>Factories and the stored columns</caption>
 *   <tr><th>#</th><th>factory</th><th>action</th><th>decided_by</th><th>requested</th>
 *       <th>actor</th><th>category + reason</th><th>system_reason</th></tr>
 *   <tr><td>1</td><td>{@link #staffApproval}</td><td>APPROVE</td><td>STAFF</td><td>APPROVE</td>
 *       <td>reviewer</td><td>—</td><td>—</td></tr>
 *   <tr><td>2</td><td>{@link #staffRejection}</td><td>REJECT</td><td>STAFF</td><td>REJECT</td>
 *       <td>reviewer</td><td>required</td><td>—</td></tr>
 *   <tr><td>3</td><td>{@link #systemRejection}</td><td>REJECT</td><td>SYSTEM</td><td>APPROVE</td>
 *       <td>reviewer</td><td>—</td><td>a wallet reason</td></tr>
 *   <tr><td>4</td><td>{@link #customerCancel}</td><td>CANCEL</td><td>CUSTOMER</td><td>CANCEL</td>
 *       <td>sender</td><td>—</td><td>—</td></tr>
 *   <tr><td>5</td><td>{@link #expiredByJob}</td><td>EXPIRE</td><td>SYSTEM</td><td>—</td>
 *       <td>—</td><td>—</td><td>REVIEW_DEADLINE_PASSED</td></tr>
 *   <tr><td>6</td><td>{@link #expiredOnLateApproval}, {@link #expiredOnLateRejection}</td>
 *       <td>EXPIRE</td><td>SYSTEM</td><td>APPROVE / REJECT</td><td>reviewer</td><td>—</td>
 *       <td>REVIEW_DEADLINE_PASSED</td></tr>
 *   <tr><td>7</td><td>{@link #expiredOnLateCancel}</td><td>EXPIRE</td><td>SYSTEM</td>
 *       <td>CANCEL</td><td>sender</td><td>—</td><td>REVIEW_DEADLINE_PASSED</td></tr>
 * </table>
 *
 * <p>Case 6 takes no text or category: a reason the reviewer typed for a late rejection is not
 * stored (D2; doc 08 §5 "From the V11 database review"), so the caller cannot pass one.
 *
 * <p>Whether the actor really is staff or the sender is not known here (the role lives in {@code
 * auth}); the caller enforces it (doc 08 §5). {@link #toString()} leaves the actor and the
 * reviewer's text out.
 */
public final class ReviewOutcome {

  /** The size of {@code risk.risk_reviews.reason} (V5); doc 06 MVP-ADR-24 amendment Q10. */
  public static final int MAX_REASON_LENGTH = 500;

  /** The field name in validation messages. */
  static final String REASON_FIELD = "reason";

  private final ReviewCase reviewCase;
  private final ReviewAction action;
  private final DecisionSource decidedBy;
  private final RequestedAction requestedAction;
  private final UUID actorUserId;
  private final StaffRejectionCategory reasonCategory;
  private final String reason;
  private final SystemReason systemReason;

  private ReviewOutcome(
      ReviewCase reviewCase,
      ReviewAction action,
      DecisionSource decidedBy,
      RequestedAction requestedAction,
      UUID actorUserId,
      StaffRejectionCategory reasonCategory,
      String reason,
      SystemReason systemReason) {
    this.reviewCase = reviewCase;
    this.action = action;
    this.decidedBy = decidedBy;
    this.requestedAction = requestedAction;
    this.actorUserId = actorUserId;
    this.reasonCategory = reasonCategory;
    this.reason = reason;
    this.systemReason = systemReason;
  }

  /** Case 1: a compliance officer approved. */
  public static ReviewOutcome staffApproval(UUID reviewer) {
    return new ReviewOutcome(
        ReviewCase.STAFF_APPROVAL,
        ReviewAction.APPROVE,
        DecisionSource.STAFF,
        RequestedAction.APPROVE,
        actor(reviewer, "reviewer"),
        null,
        null,
        null);
  }

  /**
   * Case 2: a compliance officer rejected, with a category and their own text. The text follows the
   * KYC reason text rules of MVP-ADR-10 ({@link TextRules#requireReason}) with at most {@value
   * #MAX_REASON_LENGTH} characters, and is stored normalised (NFC, stripped).
   *
   * @throws InvalidTextException if the reason breaks a rule; the message names the field only
   */
  public static ReviewOutcome staffRejection(
      UUID reviewer, StaffRejectionCategory category, String reason) {
    UUID actor = actor(reviewer, "reviewer");
    Objects.requireNonNull(category, "category");
    String checked = TextRules.requireReason(REASON_FIELD, reason, MAX_REASON_LENGTH);
    return new ReviewOutcome(
        ReviewCase.STAFF_REJECTION,
        ReviewAction.REJECT,
        DecisionSource.STAFF,
        RequestedAction.REJECT,
        actor,
        category,
        checked,
        null);
  }

  /** Case 3: an approval by {@code reviewer} found a frozen or closed wallet. */
  public static ReviewOutcome systemRejection(UUID reviewer, UnavailableWallet wallet) {
    UUID actor = actor(reviewer, "reviewer");
    Objects.requireNonNull(wallet, "wallet");
    return new ReviewOutcome(
        ReviewCase.SYSTEM_REJECTION,
        ReviewAction.REJECT,
        DecisionSource.SYSTEM,
        RequestedAction.APPROVE,
        actor,
        null,
        null,
        wallet.systemReason());
  }

  /** Case 4: the sender cancelled before the deadline. */
  public static ReviewOutcome customerCancel(UUID sender) {
    return new ReviewOutcome(
        ReviewCase.CUSTOMER_CANCEL,
        ReviewAction.CANCEL,
        DecisionSource.CUSTOMER,
        RequestedAction.CANCEL,
        actor(sender, "sender"),
        null,
        null,
        null);
  }

  /** Case 5: the scheduled job expired the transfer; no request and no actor. */
  public static ReviewOutcome expiredByJob() {
    return expired(ReviewCase.EXPIRED_BY_JOB, null, null);
  }

  /** Case 6: an approval by {@code reviewer} found the deadline passed. */
  public static ReviewOutcome expiredOnLateApproval(UUID reviewer) {
    return expired(
        ReviewCase.EXPIRED_ON_LATE_STAFF_ACTION,
        RequestedAction.APPROVE,
        actor(reviewer, "reviewer"));
  }

  /**
   * Case 6: a rejection by {@code reviewer} found the deadline passed. The reviewer's text and
   * category are not stored, so this factory takes none.
   */
  public static ReviewOutcome expiredOnLateRejection(UUID reviewer) {
    return expired(
        ReviewCase.EXPIRED_ON_LATE_STAFF_ACTION,
        RequestedAction.REJECT,
        actor(reviewer, "reviewer"));
  }

  /** Case 7: a cancel by the sender found the deadline passed. */
  public static ReviewOutcome expiredOnLateCancel(UUID sender) {
    return expired(
        ReviewCase.EXPIRED_ON_LATE_CANCEL, RequestedAction.CANCEL, actor(sender, "sender"));
  }

  private static ReviewOutcome expired(
      ReviewCase reviewCase, RequestedAction requested, UUID actor) {
    return new ReviewOutcome(
        reviewCase,
        ReviewAction.EXPIRE,
        DecisionSource.SYSTEM,
        requested,
        actor,
        null,
        null,
        SystemReason.REVIEW_DEADLINE_PASSED);
  }

  private static UUID actor(UUID actor, String name) {
    return Objects.requireNonNull(actor, name);
  }

  public ReviewCase reviewCase() {
    return reviewCase;
  }

  public ReviewAction action() {
    return action;
  }

  public DecisionSource decidedBy() {
    return decidedBy;
  }

  /** Empty only for the expiry job (case 5). */
  public Optional<RequestedAction> requestedAction() {
    return Optional.ofNullable(requestedAction);
  }

  /** Empty only for the expiry job (case 5). */
  public Optional<UUID> actorUserId() {
    return Optional.ofNullable(actorUserId);
  }

  /** Present exactly for a staff rejection (case 2). */
  public Optional<StaffRejectionCategory> reasonCategory() {
    return Optional.ofNullable(reasonCategory);
  }

  /** The reviewer's text; present exactly for a staff rejection (case 2). Staff-only. */
  public Optional<String> reason() {
    return Optional.ofNullable(reason);
  }

  /** Present exactly when the system decided (cases 3, 5, 6, 7). */
  public Optional<SystemReason> systemReason() {
    return Optional.ofNullable(systemReason);
  }

  /** The case only: the actor and the reviewer's text stay out of logs. */
  @Override
  public String toString() {
    return "ReviewOutcome[" + reviewCase + "]";
  }
}
