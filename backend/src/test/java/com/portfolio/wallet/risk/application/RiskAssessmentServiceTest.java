package com.portfolio.wallet.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.risk.domain.MvpRiskPolicy;
import com.portfolio.wallet.risk.domain.RiskDecision;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository.WindowCount;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link RiskAssessmentService} with mocks (documentations/06 MVP-ADR-24; doc 08 §4C.2 Q3 step 6,
 * Q4, Q13; criteria 3 and 4): its own transaction only, the preparer before the sender lock, the
 * lock before the count, the current transfer counted, the wallet age from database time, one
 * rule-hit row per hit, an investigation exactly for {@code BLOCK}, and every failure rolled back
 * and reported as {@code SYSTEM_ERROR}, never as score 0.
 */
class RiskAssessmentServiceTest {

  private static final Instant DB_NOW = Instant.parse("2026-11-06T03:00:00Z");
  private static final Instant OLD_WALLET = DB_NOW.minus(Duration.ofDays(30));
  private static final UUID SENDER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID TRANSFER = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID ASSESSMENT = UUID.fromString("00000000-0000-4000-8000-000000000003");
  private static final long WINDOW = 600;

  private final RiskAssessmentRepository repository = mock(RiskAssessmentRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final Runnable preparer = mock(Runnable.class);
  private final AuditService audit = mock(AuditService.class);
  private RiskAssessmentService service;

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    when(repository.insertAssessment(any(), any(), any(), anyString(), anyInt(), anyString()))
        .thenReturn(ASSESSMENT);
    service =
        new RiskAssessmentService(
            repository, MvpRiskPolicy.create(), WINDOW, audit, transactions, preparer);
  }

  private void earlierAssessments(int count) {
    when(repository.countInWindow(SENDER, WINDOW)).thenReturn(new WindowCount(DB_NOW, count));
  }

  private static RiskAssessmentRequest request(
      String amount, Instant walletCreatedAt, boolean knownRecipient) {
    return new RiskAssessmentRequest(
        TRANSFER, SENDER, Money.of(amount, Money.THB), walletCreatedAt, knownRecipient);
  }

  @Test
  void aQuietTransferIsAllowedWithoutHitsOrInvestigation() {
    earlierAssessments(0);

    RiskAssessmentResult result = service.assess(request("100.00", OLD_WALLET, true));

    assertThat(result.assessmentId()).isEqualTo(ASSESSMENT);
    assertThat(result.decision()).isEqualTo(AssessmentDecision.ALLOW);
    verify(repository)
        .insertAssessment(TRANSFER, SENDER, new BigDecimal("100.00"), "THB", 0, "ALLOW");
    verify(repository, never()).insertRuleHit(any(), anyString(), anyInt());
    verify(repository, never()).openInvestigation(any());
    verify(transactions).commit(any());
  }

  @Test
  void thePreparerRunsFirstThenTheLockThenTheCountThenTheInserts() {
    earlierAssessments(0);

    service.assess(request("100.00", OLD_WALLET, false));

    InOrder order = inOrder(transactions, preparer, repository);
    order.verify(transactions).getTransaction(any());
    order.verify(preparer).run();
    order.verify(repository).lockSender(SENDER);
    order.verify(repository).countInWindow(SENDER, WINDOW);
    order
        .verify(repository)
        .insertAssessment(any(), any(), any(), anyString(), anyInt(), anyString());
    order.verify(repository).insertRuleHit(ASSESSMENT, "NEW_RECIPIENT", 15);
    order.verify(transactions).commit(any());
  }

  @Test
  void theCurrentTransferCountsInTheFrequency() {
    // Two earlier assessments plus this one: 3 transfers, +15.
    earlierAssessments(2);

    RiskAssessmentResult result = service.assess(request("100.00", OLD_WALLET, true));

    verify(repository).insertAssessment(any(), any(), any(), anyString(), eq(15), eq("ALLOW"));
    verify(repository).insertRuleHit(ASSESSMENT, "TRANSFER_FREQUENCY", 15);
  }

  @Test
  void oneEarlierAssessmentIsNotYetAFrequencyHit() {
    earlierAssessments(1);

    service.assess(request("100.00", OLD_WALLET, true));

    verify(repository).insertAssessment(any(), any(), any(), anyString(), eq(0), eq("ALLOW"));
  }

  @Test
  void theWalletAgeIsMeasuredAgainstTheDatabaseTime() {
    earlierAssessments(0);
    // Just under 24 hours before the database's now(): +20; exactly 24 hours: +10.
    service.assess(request("100.00", DB_NOW.minus(Duration.ofHours(24)).plusNanos(1000), true));
    verify(repository).insertRuleHit(ASSESSMENT, "NEW_SENDER_WALLET", 20);

    service.assess(request("100.00", DB_NOW.minus(Duration.ofHours(24)), true));
    verify(repository).insertRuleHit(ASSESSMENT, "NEW_SENDER_WALLET", 10);
  }

  @Test
  void eachDecisionIsStoredAndReturned() {
    earlierAssessments(0);
    // 30 + 10 = 40: MONITOR.
    RiskAssessmentResult monitor =
        service.assess(request("40000.01", DB_NOW.minus(Duration.ofDays(2)), true));
    assertThat(monitor.decision()).isEqualTo(AssessmentDecision.MONITOR);

    // 4 earlier, so 5 transfers: 30 + 25 + 15 + 20 = 90: BLOCK.
    earlierAssessments(4);
    RiskAssessmentResult block =
        service.assess(request("40000.01", DB_NOW.minus(Duration.ofHours(1)), false));
    assertThat(block.decision()).isEqualTo(AssessmentDecision.BLOCK);

    // 30 + 25 + 15 = 70: MANUAL_REVIEW.
    RiskAssessmentResult review = service.assess(request("40000.01", OLD_WALLET, false));
    assertThat(review.decision()).isEqualTo(AssessmentDecision.MANUAL_REVIEW);

    verify(repository).insertAssessment(any(), any(), any(), anyString(), eq(40), eq("MONITOR"));
    verify(repository).insertAssessment(any(), any(), any(), anyString(), eq(90), eq("BLOCK"));
    verify(repository)
        .insertAssessment(any(), any(), any(), anyString(), eq(70), eq("MANUAL_REVIEW"));
  }

  @Test
  void aBlockOpensAnInvestigationAfterItsHitsInTheSameTransaction() {
    earlierAssessments(7);

    RiskAssessmentResult result =
        service.assess(request("40000.01", DB_NOW.minus(Duration.ofHours(1)), false));

    verify(repository).insertAssessment(any(), any(), any(), anyString(), eq(100), eq("BLOCK"));
    assertThat(result.decision()).isEqualTo(AssessmentDecision.BLOCK);
    InOrder order = inOrder(repository, transactions);
    order.verify(repository).insertRuleHit(ASSESSMENT, "TRANSFER_AMOUNT", 30);
    order.verify(repository).insertRuleHit(ASSESSMENT, "TRANSFER_FREQUENCY", 35);
    order.verify(repository).insertRuleHit(ASSESSMENT, "NEW_RECIPIENT", 15);
    order.verify(repository).insertRuleHit(ASSESSMENT, "NEW_SENDER_WALLET", 20);
    order.verify(repository).openInvestigation(ASSESSMENT);
    order.verify(transactions).commit(any());
  }

  @Test
  void noDecisionButBlockOpensAnInvestigation() {
    earlierAssessments(7);
    // 30 + 35 + 20 = 85: MANUAL_REVIEW.
    service.assess(request("40000.01", DB_NOW.minus(Duration.ofHours(1)), true));

    verify(repository, never()).openInvestigation(any());
  }

  // Own transaction -----------------------------------------------------------------------------

  @Test
  void itRefusesToRunInsideAnExistingTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> service.assess(request("100.00", OLD_WALLET, true)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("own transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(transactions, preparer, repository);
  }

  // Failure (criterion 4) -----------------------------------------------------------------------

  private void assertSystemErrorAndRollback() {
    assertThatThrownBy(() -> service.assess(request("40000.01", OLD_WALLET, false)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR);
              assertThat(e.getMessage()).isEqualTo(RiskAssessmentService.FAILURE_MESSAGE);
            });
    verify(transactions).rollback(any(TransactionStatus.class));
    verify(transactions, never()).commit(any());
    verify(audit, never()).record(any());
  }

  @Test
  void aFailingPreparerTakesNoLockAndIsASystemError() {
    doThrow(new IllegalStateException("timeout setting failed")).when(preparer).run();

    assertSystemErrorAndRollback();
    verifyNoInteractions(repository);
  }

  @Test
  void aFailingLockIsASystemError() {
    doThrow(new DataIntegrityViolationException("lock timeout"))
        .when(repository)
        .lockSender(SENDER);

    assertSystemErrorAndRollback();
    verify(repository, never()).insertAssessment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void aFailingCountIsASystemErrorNeverScoreZero() {
    when(repository.countInWindow(any(), anyLong())).thenThrow(new IllegalStateException("db"));

    assertSystemErrorAndRollback();
    verify(repository, never()).insertAssessment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void aFailingEvaluationIsASystemError() {
    // A wallet created after the database's now() cannot be scored: the facts are refused.
    earlierAssessments(0);

    assertThatThrownBy(() -> service.assess(request("100.00", DB_NOW.plusSeconds(1), true)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
    verify(transactions).rollback(any(TransactionStatus.class));
    verify(repository, never()).insertAssessment(any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void aFailingRuleHitInsertRollsTheAssessmentBack() {
    earlierAssessments(0);
    doThrow(new DataIntegrityViolationException("check"))
        .when(repository)
        .insertRuleHit(any(), anyString(), anyInt());

    assertSystemErrorAndRollback();
  }

  @Test
  void aFailingInvestigationRollsTheBlockBack() {
    earlierAssessments(7);
    doThrow(new DataIntegrityViolationException("investigation"))
        .when(repository)
        .openInvestigation(ASSESSMENT);

    assertThatThrownBy(
            () -> service.assess(request("40000.01", DB_NOW.minus(Duration.ofHours(1)), false)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
    verify(transactions).rollback(any(TransactionStatus.class));
    verify(transactions, never()).commit(any());
  }

  @Test
  void aFailingCommitIsASystemError() {
    earlierAssessments(0);
    doThrow(new org.springframework.transaction.TransactionSystemException("commit"))
        .when(transactions)
        .commit(any());

    assertThatThrownBy(() -> service.assess(request("100.00", OLD_WALLET, true)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
  }

  // Construction and values -----------------------------------------------------------------------

  @Test
  void theWindowMustBeAtLeastOneSecond() {
    assertThatThrownBy(
            () ->
                new RiskAssessmentService(
                    repository, MvpRiskPolicy.create(), 0, audit, transactions, preparer))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSpringConstructorTakesTheWindowFromTheProperties() {
    RiskProperties properties = mock(RiskProperties.class);
    when(properties.frequencyWindowSeconds()).thenReturn(WINDOW);
    earlierAssessments(0);

    new RiskAssessmentService(repository, MvpRiskPolicy.create(), properties, audit, transactions)
        .assess(request("100.00", OLD_WALLET, true));

    verify(repository).countInWindow(SENDER, WINDOW);
  }

  @Test
  void requestsAreChecked() {
    Money amount = Money.of("1.00", Money.THB);
    assertThatThrownBy(
            () -> new RiskAssessmentRequest(TRANSFER, SENDER, Money.zero(Money.THB), DB_NOW, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RiskAssessmentRequest(null, SENDER, amount, DB_NOW, true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RiskAssessmentRequest(TRANSFER, null, amount, DB_NOW, true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RiskAssessmentRequest(TRANSFER, SENDER, null, DB_NOW, true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RiskAssessmentRequest(TRANSFER, SENDER, amount, null, true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RiskAssessmentResult(null, AssessmentDecision.ALLOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RiskAssessmentResult(ASSESSMENT, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theResultCarriesNoScore() {
    assertThat(RiskAssessmentResult.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("assessmentId", "decision");
  }

  @Test
  void theTransactionIsReadCommitted() {
    earlierAssessments(0);

    service.assess(request("100.00", OLD_WALLET, true));

    org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionDefinition> definition =
        org.mockito.ArgumentCaptor.forClass(
            org.springframework.transaction.TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Test
  void noValueReachesToString() {
    RiskAssessmentRequest request = request("12345.67", OLD_WALLET, false);
    RiskAssessmentResult result = new RiskAssessmentResult(ASSESSMENT, AssessmentDecision.BLOCK);

    assertThat(request.toString())
        .doesNotContain("12345")
        .doesNotContain(SENDER.toString())
        .doesNotContain(TRANSFER.toString());
    assertThat(result.toString()).doesNotContain("BLOCK").doesNotContain(ASSESSMENT.toString());
  }

  @Test
  void everyDomainDecisionHasAnApplicationDecision() {
    for (RiskDecision decision : RiskDecision.values()) {
      assertThat(AssessmentDecision.of(decision).name()).isEqualTo(decision.name());
    }
    assertThat(AssessmentDecision.values()).hasSameSizeAs(RiskDecision.values());
  }

  // Audit of the risk decision (MVP-ADR-24 "Audit of risk decisions", owner 2026-11-09) --------

  private AuditEntry recordedEntry() {
    org.mockito.ArgumentCaptor<AuditEntry> entry =
        org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    return entry.getValue();
  }

  @Test
  void oneAuditRowRecordsTheDecisionAfterTheInsertsInTheSameTransaction() {
    earlierAssessments(0);

    service.assess(request("40000.01", OLD_WALLET, false));

    InOrder order = inOrder(repository, audit, transactions);
    order
        .verify(repository)
        .insertAssessment(any(), any(), any(), anyString(), anyInt(), anyString());
    order.verify(repository).insertRuleHit(ASSESSMENT, "NEW_RECIPIENT", 15);
    order.verify(audit).record(any());
    order.verify(transactions).commit(any());
    AuditEntry entry = recordedEntry();
    assertThat(entry.actorType()).isEqualTo(ActorType.CUSTOMER);
    assertThat(entry.actorId()).isEqualTo(SENDER);
    assertThat(entry.action()).isEqualTo("RISK_DECISION");
    assertThat(entry.resource()).isEqualTo("RISK_ASSESSMENT");
    assertThat(entry.resourceId()).isEqualTo(ASSESSMENT.toString());
    assertThat(entry.result()).isEqualTo(AuditResult.SUCCESS);
    assertThat(entry.clientIp()).isNull();
    assertThat(entry.oldValue()).isNull();
    // 30 + 15 = 45: MONITOR. Only the decision and the investigation flag.
    assertThat(entry.newValue())
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of("decision", "MONITOR", "investigationOpened", false));
  }

  @Test
  void theAuditRowCarriesTheClientAddressTheCallerPasses() throws Exception {
    earlierAssessments(0);
    java.net.InetAddress client = java.net.InetAddress.getByName("203.0.113.7");

    service.assess(
        new RiskAssessmentRequest(
            TRANSFER, SENDER, Money.of("100.00", Money.THB), OLD_WALLET, true, client));

    assertThat(recordedEntry().clientIp()).isEqualTo(client);
  }

  @Test
  void aBlockIsAuditedWithItsInvestigationAfterTheInvestigationInsert() {
    earlierAssessments(7);

    service.assess(request("40000.01", DB_NOW.minus(Duration.ofHours(1)), false));

    InOrder order = inOrder(repository, audit);
    order.verify(repository).openInvestigation(ASSESSMENT);
    order.verify(audit).record(any());
    AuditEntry entry = recordedEntry();
    assertThat(entry.newValue()).containsEntry("decision", "BLOCK");
    assertThat(entry.newValue()).containsEntry("investigationOpened", true);
    assertThat(entry.toString())
        .doesNotContain("40000")
        .doesNotContain("100")
        .doesNotContain("TRANSFER_")
        .doesNotContain(TRANSFER.toString());
  }

  @Test
  void aFailingAuditRowRollsTheAssessmentBack() {
    earlierAssessments(0);
    doThrow(new IllegalStateException("audit insert failed")).when(audit).record(any());

    assertThatThrownBy(() -> service.assess(request("100.00", OLD_WALLET, true)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
    verify(transactions).rollback(any(TransactionStatus.class));
    verify(transactions, never()).commit(any());
  }

  @Test
  void anAssessmentThatFailsBeforeItsInsertsIsNotAuditedHere() {
    when(repository.countInWindow(any(), anyLong())).thenThrow(new IllegalStateException("db"));

    assertThatThrownBy(() -> service.assess(request("100.00", OLD_WALLET, true)))
        .isInstanceOf(ApiException.class);
    // The transfer module audits the failure independently (step 5).
    verifyNoInteractions(audit);
  }

  @Test
  void anAuditServiceIsRequired() {
    assertThatThrownBy(
            () ->
                new RiskAssessmentService(
                    repository, MvpRiskPolicy.create(), WINDOW, null, transactions, preparer))
        .isInstanceOf(NullPointerException.class);
  }
}
