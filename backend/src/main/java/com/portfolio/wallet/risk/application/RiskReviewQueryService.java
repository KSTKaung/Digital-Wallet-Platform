package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository.AssessmentRow;
import com.portfolio.wallet.risk.persistence.RiskReviewRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff reads of the risk schema for the transfer review (documentations/02 §2.7; doc 06
 * MVP-ADR-24 "Security implications", amendment "Review queue (Q12)"): the score, decision and
 * triggered rules of an assessment, and the review row ("history"). Internal, staff-only data: only
 * the {@code admin} module calls this, for compliance officers; nothing here is logged.
 */
@Service
public class RiskReviewQueryService {

  private final RiskAssessmentRepository assessments;
  private final RiskReviewRepository reviews;

  public RiskReviewQueryService(
      RiskAssessmentRepository assessments, RiskReviewRepository reviews) {
    this.assessments = Objects.requireNonNull(assessments, "assessments");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
  }

  /** A triggered rule and its weight. */
  public record RuleHitView(String ruleCode, int weight) {

    @Override
    public String toString() {
      return "RuleHitView[…]";
    }
  }

  /** An assessment with its triggered rules, highest weight first. */
  public record AssessmentView(
      UUID assessmentId, int score, String decision, Instant assessedAt, List<RuleHitView> rules) {

    public AssessmentView {
      rules = List.copyOf(rules);
    }

    @Override
    public String toString() {
      return "AssessmentView[id=" + assessmentId + "]";
    }
  }

  /**
   * A review row with the five facts of D2 and the reviewer's text (staff-only; present exactly for
   * a staff rejection).
   */
  public record ReviewView(
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
      return "ReviewView[action=" + action + ", decidedBy=" + decidedBy + "]";
    }
  }

  /** The scores of these assessments, by id; an id without an assessment is left out. */
  @Transactional(propagation = Propagation.NEVER, readOnly = true)
  public Map<UUID, Integer> scores(Collection<UUID> assessmentIds) {
    Objects.requireNonNull(assessmentIds, "assessmentIds");
    Map<UUID, Integer> scores = new HashMap<>();
    for (AssessmentRow row : assessments.findAssessments(assessmentIds)) {
      scores.put(row.id(), row.score());
    }
    return scores;
  }

  /**
   * @throws IllegalStateException if the assessment does not exist (every transfer references a
   *     committed assessment; assessments are never deleted)
   */
  @Transactional(propagation = Propagation.NEVER, readOnly = true)
  public AssessmentView assessment(UUID assessmentId) {
    Objects.requireNonNull(assessmentId, "assessmentId");
    AssessmentRow row =
        assessments.findAssessments(List.of(assessmentId)).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("a transfer's assessment is missing"));
    return new AssessmentView(
        row.id(),
        row.score(),
        row.decision(),
        row.assessedAt(),
        assessments.findRuleHits(assessmentId).stream()
            .map(hit -> new RuleHitView(hit.ruleCode(), hit.weight()))
            .toList());
  }

  /** The review row of the transfer; empty while it is still held. */
  @Transactional(propagation = Propagation.NEVER, readOnly = true)
  public Optional<ReviewView> review(UUID transferId) {
    Objects.requireNonNull(transferId, "transferId");
    return reviews
        .findByTransfer(transferId)
        .map(
            row ->
                new ReviewView(
                    row.action(),
                    row.decidedBy(),
                    row.requestedAction(),
                    row.actorUserId(),
                    row.reasonCategory(),
                    row.reason(),
                    row.systemReason(),
                    row.createdAt()));
  }
}
