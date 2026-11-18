package com.portfolio.wallet.admin.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.risk.application.RiskReviewQueryService;
import com.portfolio.wallet.risk.application.RiskReviewQueryService.AssessmentView;
import com.portfolio.wallet.transfer.application.HeldTransferPage;
import com.portfolio.wallet.transfer.application.HeldTransferView;
import com.portfolio.wallet.transfer.application.TransferReviewDecisionService;
import com.portfolio.wallet.transfer.application.TransferReviewQueryService;
import com.portfolio.wallet.user.application.CustomerLegalNames;
import com.portfolio.wallet.user.application.CustomerLegalNames.LegalNameView;
import com.portfolio.wallet.wallet.application.TransferCounterparties;
import com.portfolio.wallet.wallet.application.WalletLookupView;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The compliance officer's transfer review (documentations/02 §2.7; doc 06 MVP-ADR-12, MVP-ADR-24
 * "Review" and amendment "Review queue (Q12)"; doc 08 §4C.2 Q12, §4C.3, §4C.4 criterion 15): the
 * queue, the detail, approve and reject. The reviewer is the JWT subject; {@code SecurityConfig}
 * admits only {@code COMPLIANCE_OFFICER}.
 *
 * <p>This service composes the modules and opens no transaction of its own:
 *
 * <ul>
 *   <li>the decision runs in the transfer module's own transaction ({@link
 *       TransferReviewDecisionService}): the transfer row lock first, then the wallets, the usage,
 *       the status, the {@code risk} review row, the audit row and the emails, committed together;
 *   <li>the detail is read after the commit, from the transfer, risk, user (full legal names; only
 *       this module may read them) and wallet (masked numbers) modules, so no name is read under
 *       the money locks. A decision committed between those reads can make one detail slightly
 *       behind; the next read is current.
 * </ul>
 */
@Service
public class TransferReviewService {

  private final TransferReviewQueryService transfers;
  private final TransferReviewDecisionService decisions;
  private final RiskReviewQueryService risk;
  private final CustomerLegalNames names;
  private final TransferCounterparties wallets;

  public TransferReviewService(
      TransferReviewQueryService transfers,
      TransferReviewDecisionService decisions,
      RiskReviewQueryService risk,
      CustomerLegalNames names,
      TransferCounterparties wallets) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.decisions = Objects.requireNonNull(decisions, "decisions");
    this.risk = Objects.requireNonNull(risk, "risk");
    this.names = Objects.requireNonNull(names, "names");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
  }

  /**
   * The {@code PENDING_REVIEW} transfers, soonest deadline first, then by id.
   *
   * @throws ApiException {@code VALIDATION_ERROR} for a page or size out of range
   */
  public TransferReviewPage queue(int page, int size) {
    HeldTransferPage held = transfers.queue(page, size);
    Map<UUID, Integer> scores =
        risk.scores(held.items().stream().map(HeldTransferView::riskAssessmentId).toList());
    List<TransferReviewSummary> items =
        held.items().stream()
            .map(
                view ->
                    new TransferReviewSummary(
                        view.id(),
                        view.reference(),
                        view.amount(),
                        view.currency(),
                        view.heldAt(),
                        view.reviewExpiresAt(),
                        scores.get(view.riskAssessmentId())))
            .toList();
    return new TransferReviewPage(
        items, held.page(), held.size(), held.totalElements(), held.totalPages());
  }

  /**
   * @throws ApiException the identical {@code NOT_FOUND} for a missing id or a transfer that was
   *     never held for review
   */
  public TransferReviewDetail get(UUID transferId) {
    return detail(transfers.get(transferId));
  }

  /** Approves, then returns the detail as committed (MVP-ADR-24 cases 1 and 3). */
  public TransferReviewDetail approve(UUID reviewerId, UUID transferId, InetAddress clientIp) {
    decisions.approve(reviewerId, transferId, clientIp);
    return get(transferId);
  }

  /** Rejects with the reviewer's reason, then returns the detail as committed (case 2). */
  public TransferReviewDetail reject(
      UUID reviewerId, UUID transferId, String reason, InetAddress clientIp) {
    decisions.reject(reviewerId, transferId, reason, clientIp);
    return get(transferId);
  }

  private TransferReviewDetail detail(HeldTransferView view) {
    AssessmentView assessment = risk.assessment(view.riskAssessmentId());
    Map<UUID, LegalNameView> legalNames =
        names.of(List.of(view.senderCustomerId(), view.receiverCustomerId()));
    Map<UUID, WalletLookupView> numbers =
        wallets.of(List.of(view.senderWalletId(), view.receiverWalletId()));
    List<TransferReviewDetail.History> history =
        risk.review(view.id()).stream()
            .map(
                review ->
                    new TransferReviewDetail.History(
                        review.action(),
                        review.decidedBy(),
                        review.requestedAction(),
                        review.actorUserId(),
                        review.reasonCategory(),
                        review.reason(),
                        review.systemReason(),
                        review.recordedAt()))
            .toList();
    return new TransferReviewDetail(
        view.id(),
        view.reference(),
        view.status(),
        view.amount(),
        view.currency(),
        view.createdAt(),
        view.heldAt(),
        view.reviewExpiresAt(),
        view.decidedAt(),
        view.rejectionReasonCategory(),
        view.ledgerStatus(),
        new TransferReviewDetail.Risk(
            assessment.score(),
            assessment.decision(),
            assessment.assessedAt(),
            assessment.rules().stream()
                .map(hit -> new TransferReviewDetail.RuleHit(hit.ruleCode(), hit.weight()))
                .toList()),
        party(view.senderCustomerId(), view.senderWalletId(), legalNames, numbers),
        party(view.receiverCustomerId(), view.receiverWalletId(), legalNames, numbers),
        history);
  }

  private static TransferReviewDetail.Party party(
      UUID customerId,
      UUID walletId,
      Map<UUID, LegalNameView> legalNames,
      Map<UUID, WalletLookupView> numbers) {
    LegalNameView name = legalNames.get(customerId);
    return new TransferReviewDetail.Party(
        customerId, name.firstName(), name.lastName(), numbers.get(walletId).maskedWalletNumber());
  }
}
