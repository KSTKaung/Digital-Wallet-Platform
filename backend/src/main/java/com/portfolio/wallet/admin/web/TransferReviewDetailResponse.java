package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.admin.application.TransferReviewDetail;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /admin/transfer-reviews/{transferId}} and the decision responses (documentations/02
 * §2.7; doc 06 MVP-ADR-24 amendment "Review queue (Q12)"): staff-only. {@link #toString()} keeps
 * the id and status only.
 */
record TransferReviewDetailResponse(
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

  record Risk(int score, String decision, Instant assessedAt, List<RuleHit> rules) {}

  record RuleHit(String code, int weight) {}

  record Party(UUID customerId, String firstName, String lastName, String maskedWalletNumber) {

    @Override
    public String toString() {
      return "Party[customerId=" + customerId + "]";
    }
  }

  record History(
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
      return "History[action=" + action + "]";
    }
  }

  static TransferReviewDetailResponse from(TransferReviewDetail detail) {
    TransferReviewDetail.Risk risk = detail.risk();
    return new TransferReviewDetailResponse(
        detail.transferId(),
        detail.reference(),
        detail.status(),
        detail.amount(),
        detail.currency(),
        detail.createdAt(),
        detail.heldAt(),
        detail.reviewExpiresAt(),
        detail.decidedAt(),
        detail.rejectionReasonCategory(),
        detail.ledgerStatus(),
        new Risk(
            risk.score(),
            risk.decision(),
            risk.assessedAt(),
            risk.rules().stream().map(hit -> new RuleHit(hit.code(), hit.weight())).toList()),
        party(detail.sender()),
        party(detail.recipient()),
        detail.history().stream()
            .map(
                h ->
                    new History(
                        h.action(),
                        h.decidedBy(),
                        h.requestedAction(),
                        h.actorUserId(),
                        h.reasonCategory(),
                        h.reason(),
                        h.systemReason(),
                        h.recordedAt()))
            .toList());
  }

  private static Party party(TransferReviewDetail.Party party) {
    return new Party(
        party.customerId(), party.firstName(), party.lastName(), party.maskedWalletNumber());
  }

  @Override
  public String toString() {
    return "TransferReviewDetailResponse[transferId=" + transferId + ", status=" + status + "]";
  }
}
