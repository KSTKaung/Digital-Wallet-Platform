package com.portfolio.wallet.admin.web;

import com.portfolio.wallet.admin.application.TransferReviewDetail;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(nullable = true, description = "Null while PENDING_REVIEW") Instant decidedAt,
    @Schema(
            nullable = true,
            description = "REVIEW_DECLINED or WALLET_UNAVAILABLE for REJECTED, otherwise null")
        String rejectionReasonCategory,
    @Schema(nullable = true, description = "POSTED for COMPLETED, otherwise null")
        String ledgerStatus,
    Risk risk,
    Party sender,
    Party recipient,
    List<History> history) {

  record Risk(int score, String decision, Instant assessedAt, List<RuleHit> rules) {}

  record RuleHit(
      @Schema(
              description =
                  "TRANSFER_AMOUNT, TRANSFER_FREQUENCY, NEW_RECIPIENT or NEW_SENDER_WALLET"
                      + " (documentations/06 MVP-ADR-24)")
          String code,
      int weight) {}

  record Party(UUID customerId, String firstName, String lastName, String maskedWalletNumber) {

    @Override
    public String toString() {
      return "Party[customerId=" + customerId + "]";
    }
  }

  record History(
      @Schema(description = "APPROVE, REJECT, CANCEL or EXPIRE") String action,
      @Schema(description = "STAFF, CUSTOMER or SYSTEM") String decidedBy,
      @Schema(nullable = true, description = "Null for an expiry by the scheduled job")
          String requestedAction,
      @Schema(nullable = true, description = "Null for an expiry by the scheduled job")
          UUID actorUserId,
      @Schema(nullable = true, description = "STAFF_REJECTION for a staff rejection, else null")
          String reasonCategory,
      @Schema(nullable = true, description = "The reviewer's text for a staff rejection, else null")
          String reason,
      @Schema(nullable = true, description = "The system reason when the system decided, else null")
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
