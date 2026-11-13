package com.portfolio.wallet.admin.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The staff detail of a transfer held for review (documentations/02 §2.7; doc 06 MVP-ADR-24
 * amendment "Review queue (Q12)"): amount, times, score, rule codes and weights, both parties' full
 * legal names and masked wallet numbers, and the review history (the review row, once decided: "the
 * review detail shows staff the whole row"). Internal and Confidential data for compliance officers
 * only; never the customer's description. {@link #toString()} keeps the id and status.
 */
public record TransferReviewDetail(
    UUID transferId,
    String reference,
    String status,
    BigDecimal amount,
    String currency,
    Instant createdAt,
    Instant heldAt,
    Instant reviewExpiresAt,
    Instant decidedAt,
    String rejectionReasonCategory,
    String ledgerStatus,
    Risk risk,
    Party sender,
    Party recipient,
    List<History> history) {

  public TransferReviewDetail {
    history = List.copyOf(history);
  }

  /** The risk assessment: score, decision and the triggered rules, highest weight first. */
  public record Risk(int score, String decision, Instant assessedAt, List<RuleHit> rules) {

    public Risk {
      rules = List.copyOf(rules);
    }

    @Override
    public String toString() {
      return "Risk[…]";
    }
  }

  /** One triggered rule. */
  public record RuleHit(String code, int weight) {

    @Override
    public String toString() {
      return "RuleHit[…]";
    }
  }

  /** A party: customer id, full legal name, masked wallet number. */
  public record Party(
      UUID customerId, String firstName, String lastName, String maskedWalletNumber) {

    @Override
    public String toString() {
      return "Party[customerId=" + customerId + "]";
    }
  }

  /**
   * One review outcome, with the five facts of D2 and the reviewer's own text (staff-only; present
   * only for a staff rejection).
   */
  public record History(
      String action,
      String decidedBy,
      String requestedAction,
      UUID actorUserId,
      String reasonCategory,
      String reason,
      String systemReason,
      Instant recordedAt) {

    @Override
    public String toString() {
      return "History[action=" + action + ", decidedBy=" + decidedBy + "]";
    }
  }

  @Override
  public String toString() {
    return "TransferReviewDetail[transferId=" + transferId + ", status=" + status + "]";
  }
}
