package com.portfolio.wallet.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.risk.application.RiskReviewQueryService.AssessmentView;
import com.portfolio.wallet.risk.application.RiskReviewQueryService.ReviewView;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository.AssessmentRow;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository.RuleHitRow;
import com.portfolio.wallet.risk.persistence.RiskReviewRepository;
import com.portfolio.wallet.risk.persistence.RiskReviewRepository.ReviewRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The staff reads of the risk schema (doc 06 MVP-ADR-24 amendment Q12). */
class RiskReviewQueryServiceTest {

  private static final Instant AT = Instant.parse("2026-11-10T05:00:00Z");

  private final RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
  private final RiskReviewRepository reviews = mock(RiskReviewRepository.class);
  private final RiskReviewQueryService service = new RiskReviewQueryService(assessments, reviews);

  @Test
  void scoresAreKeyedByAssessment() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(assessments.findAssessments(List.of(a, b)))
        .thenReturn(List.of(new AssessmentRow(a, 70, "MANUAL_REVIEW", AT)));

    assertThat(service.scores(List.of(a, b))).containsExactlyEntriesOf(java.util.Map.of(a, 70));
  }

  @Test
  void anAssessmentComesWithItsRules() {
    UUID id = UUID.randomUUID();
    when(assessments.findAssessments(List.of(id)))
        .thenReturn(List.of(new AssessmentRow(id, 89, "MANUAL_REVIEW", AT)));
    when(assessments.findRuleHits(id))
        .thenReturn(
            List.of(new RuleHitRow("TRANSFER_AMOUNT", 86), new RuleHitRow("NEW_RECIPIENT", 1)));

    AssessmentView view = service.assessment(id);

    assertThat(view.score()).isEqualTo(89);
    assertThat(view.decision()).isEqualTo("MANUAL_REVIEW");
    assertThat(view.rules())
        .extracting(RiskReviewQueryService.RuleHitView::ruleCode)
        .containsExactly("TRANSFER_AMOUNT", "NEW_RECIPIENT");
    assertThat(view.toString()).isEqualTo("AssessmentView[id=" + id + "]");
  }

  @Test
  void aMissingAssessmentIsAProgrammingError() {
    UUID id = UUID.randomUUID();
    when(assessments.findAssessments(List.of(id))).thenReturn(List.of());
    assertThatThrownBy(() -> service.assessment(id)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theReviewRowIsTheWholeRowAndItsToStringHidesTheReason() {
    UUID transfer = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    when(reviews.findByTransfer(transfer))
        .thenReturn(
            Optional.of(
                new ReviewRow(
                    "REJECT", "STAFF", "REJECT", actor, "STAFF_REJECTION", "secret", null, AT)));

    ReviewView view = service.review(transfer).orElseThrow();

    assertThat(view.reason()).isEqualTo("secret");
    assertThat(view.actorUserId()).isEqualTo(actor);
    assertThat(view.toString()).doesNotContain("secret", actor.toString());
    assertThat(service.review(UUID.randomUUID())).isEmpty();
  }
}
