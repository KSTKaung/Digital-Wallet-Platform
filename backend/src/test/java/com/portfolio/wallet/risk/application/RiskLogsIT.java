package com.portfolio.wallet.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.risk.domain.MvpRiskPolicy;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * No log line of the risk paths carries a score, a rule code, a weight, an amount, an id or the
 * reviewer's reason (documentations/06 MVP-ADR-24 "Security implications"; doc 08 §4C.4 criterion
 * 20). Every outcome is driven, including a failed assessment, which is the path that logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class RiskLogsIT extends DatabaseIntegrationTest {

  @Autowired RiskAssessmentService service;
  @Autowired RiskReviewService reviews;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcClient jdbcClient;
  @Autowired com.portfolio.wallet.audit.application.AuditService auditService;

  @Test
  void riskPathsLogNoScoreRuleAmountIdOrReason(CapturedOutput output) {
    int start = output.getAll().length();
    UUID sender = UUID.randomUUID();
    List<String> secrets = new ArrayList<>();
    secrets.add(sender.toString());

    // ALLOW, MANUAL_REVIEW and BLOCK, with distinctive amounts.
    List<RiskAssessmentResult> results = new ArrayList<>();
    for (String amount : List.of("123.45", "234.56", "345.67", "456.78")) {
      results.add(assess(sender, amount, Instant.now().minus(Duration.ofDays(30)), true, secrets));
    }
    results.add(
        assess(sender, "40123.45", Instant.now().minus(Duration.ofHours(1)), false, secrets));
    assertThat(results.getLast().decision()).isEqualTo(AssessmentDecision.BLOCK);
    // Sixth transfer, +25: 30 + 25 + 15 = 70.
    results.add(
        assess(sender, "40234.56", Instant.now().minus(Duration.ofDays(30)), false, secrets));
    assertThat(results.getLast().decision()).isEqualTo(AssessmentDecision.MANUAL_REVIEW);
    for (RiskAssessmentResult result : results) {
      secrets.add(result.assessmentId().toString());
    }

    // A failed assessment: the hit insert fails after the assessment insert.
    RiskAssessmentRepository failing =
        new RiskAssessmentRepository(jdbcClient) {
          @Override
          public void insertRuleHit(UUID assessmentId, String ruleCode, int weight) {
            throw new IllegalStateException("injected failure");
          }
        };
    RiskAssessmentService broken =
        new RiskAssessmentService(
            failing, MvpRiskPolicy.create(), 600, auditService, transactionManager, () -> {});
    RiskAssessmentRequest failed =
        new RiskAssessmentRequest(
            UUID.randomUUID(),
            sender,
            Money.of("41234.56", Money.THB),
            Instant.now().minus(Duration.ofHours(1)),
            false);
    secrets.add(failed.transferId().toString());
    secrets.add("41234.56");
    assertThatThrownBy(() -> broken.assess(failed)).isInstanceOf(ApiException.class);

    // A failure that comes from the database: a second assessment of one transfer
    // (uq_risk_assessments_transfer).
    RiskAssessmentRequest once =
        new RiskAssessmentRequest(
            UUID.randomUUID(),
            sender,
            Money.of("42345.67", Money.THB),
            Instant.now().minus(Duration.ofDays(30)),
            true);
    secrets.add(once.transferId().toString());
    secrets.add("42345.67");
    RiskAssessmentResult first = service.assess(once);
    secrets.add(first.assessmentId().toString());
    assertThatThrownBy(() -> service.assess(once)).isInstanceOf(ApiException.class);

    // A staff rejection with a distinctive reason, recorded in a transaction.
    UUID reviewer = UUID.randomUUID();
    secrets.add(reviewer.toString());
    secrets.add("reason-7c1e");
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                reviews.record(
                    transferOf(results.get(0)),
                    results.get(0).assessmentId(),
                    RiskReviewOutcome.staffRejection(reviewer, "reason-7c1e private note")));

    String logged = output.getAll().substring(start);
    assertThat(logged).as("captured output").contains("Risk assessment failed");
    // Both failures were logged inside the captured window, the database one included.
    assertThat(logged).contains("injected failure").contains("uq_risk_assessments_transfer");
    for (String secret : secrets) {
      assertThat(logged).as("log must not contain a sensitive value").doesNotContain(secret);
    }
    for (String code :
        List.of("TRANSFER_AMOUNT", "TRANSFER_FREQUENCY", "NEW_RECIPIENT", "NEW_SENDER_WALLET")) {
      assertThat(logged).as("log must not name a rule").doesNotContain(code);
    }
    // No score field or value label (the stack trace names this test method, so the bare word may
    // appear).
    assertThat(logged)
        .doesNotContain("score=")
        .doesNotContain("score:")
        .doesNotContain("\"score\"")
        .doesNotContain("decision=")
        // No decision band either.
        .doesNotContain("MANUAL_REVIEW")
        .doesNotContain("BLOCK");
  }

  private RiskAssessmentResult assess(
      UUID sender, String amount, Instant walletCreatedAt, boolean known, List<String> secrets) {
    RiskAssessmentRequest request =
        new RiskAssessmentRequest(
            UUID.randomUUID(), sender, Money.of(amount, Money.THB), walletCreatedAt, known);
    secrets.add(request.transferId().toString());
    secrets.add(amount);
    return service.assess(request);
  }

  private UUID transferOf(RiskAssessmentResult result) {
    return transferOfAssessment(result.assessmentId());
  }
}
