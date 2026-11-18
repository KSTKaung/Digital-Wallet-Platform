package com.portfolio.wallet.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.risk.application.RiskReviewQueryService;
import com.portfolio.wallet.risk.application.RiskReviewQueryService.AssessmentView;
import com.portfolio.wallet.risk.application.RiskReviewQueryService.ReviewView;
import com.portfolio.wallet.risk.application.RiskReviewQueryService.RuleHitView;
import com.portfolio.wallet.transfer.application.HeldTransferPage;
import com.portfolio.wallet.transfer.application.HeldTransferView;
import com.portfolio.wallet.transfer.application.TransferReviewDecisionService;
import com.portfolio.wallet.transfer.application.TransferReviewQueryService;
import com.portfolio.wallet.user.application.CustomerLegalNames;
import com.portfolio.wallet.user.application.CustomerLegalNames.LegalNameView;
import com.portfolio.wallet.wallet.application.TransferCounterparties;
import com.portfolio.wallet.wallet.application.WalletLookupView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** How the admin module composes the transfer review (doc 06 MVP-ADR-24 amendment Q12). */
class TransferReviewServiceTest {

  private static final UUID REVIEWER = UUID.randomUUID();
  private static final UUID TRANSFER = UUID.randomUUID();
  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID RECEIVER = UUID.randomUUID();
  private static final UUID SENDER_WALLET = UUID.randomUUID();
  private static final UUID RECEIVER_WALLET = UUID.randomUUID();
  private static final UUID ASSESSMENT = UUID.randomUUID();
  private static final Instant HELD_AT = Instant.parse("2026-11-10T05:00:00Z");

  private final TransferReviewQueryService transfers = mock(TransferReviewQueryService.class);
  private final TransferReviewDecisionService decisions = mock(TransferReviewDecisionService.class);
  private final RiskReviewQueryService risk = mock(RiskReviewQueryService.class);
  private final CustomerLegalNames names = mock(CustomerLegalNames.class);
  private final TransferCounterparties wallets = mock(TransferCounterparties.class);
  private final TransferReviewService service =
      new TransferReviewService(transfers, decisions, risk, names, wallets);

  private static HeldTransferView view(UUID id, UUID assessment, String status) {
    return new HeldTransferView(
        id,
        "TRF-1",
        status,
        new BigDecimal("1300.00"),
        "THB",
        SENDER,
        RECEIVER,
        SENDER_WALLET,
        RECEIVER_WALLET,
        assessment,
        HELD_AT,
        HELD_AT,
        HELD_AT.plusSeconds(86_400),
        null,
        null,
        null);
  }

  @BeforeEach
  void setUp() {
    when(transfers.get(TRANSFER)).thenReturn(view(TRANSFER, ASSESSMENT, "PENDING_REVIEW"));
    when(risk.assessment(ASSESSMENT))
        .thenReturn(
            new AssessmentView(
                ASSESSMENT,
                70,
                "MANUAL_REVIEW",
                HELD_AT,
                List.of(
                    new RuleHitView("TRANSFER_AMOUNT", 67), new RuleHitView("NEW_RECIPIENT", 3))));
    when(risk.review(TRANSFER)).thenReturn(Optional.empty());
    when(names.of(List.of(SENDER, RECEIVER)))
        .thenReturn(
            Map.of(
                SENDER, new LegalNameView("Somchai", "Jaidee"),
                RECEIVER, new LegalNameView("Malee", "Suksan")));
    when(wallets.of(List.of(SENDER_WALLET, RECEIVER_WALLET)))
        .thenReturn(
            Map.of(
                SENDER_WALLET, new WalletLookupView("Somchai J.", "*******1111"),
                RECEIVER_WALLET, new WalletLookupView("Malee S.", "*******2222")));
  }

  @Test
  void theQueueAddsEachTransfersScore() {
    UUID other = UUID.randomUUID();
    UUID otherAssessment = UUID.randomUUID();
    when(transfers.queue(1, 2))
        .thenReturn(
            new HeldTransferPage(
                List.of(
                    view(TRANSFER, ASSESSMENT, "PENDING_REVIEW"),
                    view(other, otherAssessment, "PENDING_REVIEW")),
                1,
                2,
                5,
                3));
    when(risk.scores(List.of(ASSESSMENT, otherAssessment)))
        .thenReturn(Map.of(ASSESSMENT, 70, otherAssessment, 89));

    TransferReviewPage page = service.queue(1, 2);

    assertThat(page.items())
        .extracting(TransferReviewSummary::transferId)
        .containsExactly(TRANSFER, other);
    assertThat(page.items()).extracting(TransferReviewSummary::score).containsExactly(70, 89);
    assertThat(page.totalElements()).isEqualTo(5);
    assertThat(page.totalPages()).isEqualTo(3);
  }

  @Test
  void theDetailHasScoreRulesFullNamesMaskedNumbersAndNoHistoryWhileHeld() {
    TransferReviewDetail detail = service.get(TRANSFER);

    assertThat(detail.risk().score()).isEqualTo(70);
    assertThat(detail.risk().rules())
        .extracting(TransferReviewDetail.RuleHit::code)
        .containsExactly("TRANSFER_AMOUNT", "NEW_RECIPIENT");
    assertThat(detail.sender())
        .isEqualTo(new TransferReviewDetail.Party(SENDER, "Somchai", "Jaidee", "*******1111"));
    assertThat(detail.recipient())
        .isEqualTo(new TransferReviewDetail.Party(RECEIVER, "Malee", "Suksan", "*******2222"));
    assertThat(detail.history()).isEmpty();
    assertThat(detail.toString()).doesNotContain("Somchai", "Jaidee", "1111");
  }

  @Test
  void theHistoryIsTheReviewRow() {
    Instant at = HELD_AT.plusSeconds(60);
    when(risk.review(TRANSFER))
        .thenReturn(
            Optional.of(
                new ReviewView(
                    "REJECT", "STAFF", "REJECT", REVIEWER, "STAFF_REJECTION", "why", null, at)));

    assertThat(service.get(TRANSFER).history())
        .containsExactly(
            new TransferReviewDetail.History(
                "REJECT", "STAFF", "REJECT", REVIEWER, "STAFF_REJECTION", "why", null, at));
  }

  @Test
  void decisionsRunFirstAndTheDetailIsReadAfterTheirCommit() {
    service.approve(REVIEWER, TRANSFER, null);
    service.reject(REVIEWER, TRANSFER, "why", null);

    InOrder order = inOrder(decisions, transfers);
    order.verify(decisions).approve(REVIEWER, TRANSFER, null);
    order.verify(transfers).get(TRANSFER);
    order.verify(decisions).reject(REVIEWER, TRANSFER, "why", null);
    order.verify(transfers).get(TRANSFER);
  }

  @Test
  void aRefusedDecisionReadsNoDetail() {
    doThrow(new ApiException(ErrorCode.INVALID_TRANSFER_STATE, "x"))
        .when(decisions)
        .approve(any(), any(), any());

    assertThatThrownBy(() -> service.approve(REVIEWER, TRANSFER, null))
        .isInstanceOf(ApiException.class);
    verify(transfers, never()).get(any());
  }
}
