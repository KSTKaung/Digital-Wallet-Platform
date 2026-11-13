package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.risk.application.RiskReviewOutcome;
import com.portfolio.wallet.risk.application.RiskReviewService;
import com.portfolio.wallet.transfer.application.HeldTransferDecider.Request;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import com.portfolio.wallet.wallet.application.ApprovalOutcome;
import com.portfolio.wallet.wallet.application.LockedTransferWallets;
import com.portfolio.wallet.wallet.application.TransferSettlement;
import com.portfolio.wallet.wallet.application.TransferWalletView;
import com.portfolio.wallet.wallet.application.UnavailableWallet;
import com.portfolio.wallet.wallet.application.WalletTransferService;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Rules of {@link TransferReviewDecisionService} and the staff paths of {@link HeldTransferDecider}
 * (documentations/06 MVP-ADR-24 "Review", review cases 1, 2, 3 and 6; doc 08 §4C.4 criterion 15; §5
 * notes: deadline before the reason, a late rejection drops the reason).
 */
class TransferReviewDecisionServiceTest {

  private static final UUID REVIEWER = UUID.randomUUID();
  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID RECEIVER = UUID.randomUUID();
  private static final UUID SENDER_WALLET = UUID.randomUUID();
  private static final UUID RECEIVER_WALLET = UUID.randomUUID();
  private static final UUID TRANSFER = UUID.randomUUID();
  private static final UUID ASSESSMENT = UUID.randomUUID();
  private static final UUID LEDGER_TRANSACTION = UUID.randomUUID();
  private static final Instant HELD_AT = Instant.parse("2026-11-10T05:00:00Z");
  private static final Instant DEADLINE = HELD_AT.plus(Duration.ofHours(24));
  private static final LocalDate LIMIT_DATE = LocalDate.parse("2026-11-10");
  private static final Money AMOUNT = Money.of("1300.00", Money.THB);
  private static final String REASON = "Payment pattern matches a known scam secret-7f3e";
  private static final Map<String, String> EMAIL_VALUES =
      Map.of("amount", "1300.00", "currency", "THB", "reference", "TRF-1");

  private final TransferRepository transfers = mock(TransferRepository.class);
  private final WalletTransferService wallets = mock(WalletTransferService.class);
  private final RiskReviewService reviews = mock(RiskReviewService.class);
  private final AuditService audit = mock(AuditService.class);
  private final TransactionTimeouts timeouts = mock(TransactionTimeouts.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final LockedTransferWallets locked = mock(LockedTransferWallets.class);
  private final NotificationOutbox outbox = mock(NotificationOutbox.class);

  private final HeldTransferDecider decider =
      new HeldTransferDecider(transfers, wallets, reviews, audit, new TransferEmails(outbox));
  private final TransferReviewDecisionService service =
      new TransferReviewDecisionService(transfers, decider, timeouts, transactions);

  private static TransferRow row(
      String status, Instant decidedAt, String category, String ledger, long version) {
    return new TransferRow(
        TRANSFER,
        "TRF-1",
        SENDER,
        RECEIVER,
        SENDER_WALLET,
        RECEIVER_WALLET,
        new BigDecimal("1300.00"),
        "THB",
        "rent",
        status,
        UUID.randomUUID(),
        LIMIT_DATE,
        ASSESSMENT,
        HELD_AT,
        DEADLINE,
        decidedAt,
        category,
        ledger,
        null,
        version,
        HELD_AT,
        HELD_AT);
  }

  private static TransferRow held() {
    return row("PENDING_REVIEW", null, null, null, 2);
  }

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any()))
        .thenAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return new SimpleTransactionStatus();
            });
    org.mockito.Mockito.doAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactions)
        .commit(any());
    org.mockito.Mockito.doAnswer(
            i -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactions)
        .rollback(any());
    when(transfers.lockHeldForReview(TRANSFER)).thenReturn(Optional.of(held()));
    when(transfers.databaseNow()).thenReturn(DEADLINE.minusSeconds(1));
    when(wallets.lock(SENDER_WALLET, RECEIVER_WALLET)).thenReturn(locked);
    when(locked.sender())
        .thenReturn(
            new TransferWalletView(
                SENDER_WALLET,
                SENDER,
                "ACTIVE",
                "BASIC",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                HELD_AT));
    when(transfers.updateDecision(eq(TRANSFER), any(), any(), any(), eq(2L)))
        .thenAnswer(
            i ->
                Optional.of(
                    row(i.getArgument(1), DEADLINE, i.getArgument(2), i.getArgument(3), 3)));
    when(wallets.approve(locked, AMOUNT, LIMIT_DATE, "TRF-1"))
        .thenReturn(
            new ApprovalOutcome.Captured(new TransferSettlement(LIMIT_DATE, LEDGER_TRANSACTION)));
  }

  @AfterEach
  void endTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  private String reviewCase() {
    ArgumentCaptor<RiskReviewOutcome> review = ArgumentCaptor.forClass(RiskReviewOutcome.class);
    verify(reviews).record(eq(TRANSFER), eq(ASSESSMENT), review.capture());
    return review.getValue().toString();
  }

  private AuditEntry auditEntry() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    return entry.getValue();
  }

  private void assertNothingWritten() {
    verify(wallets, never()).release(any(), any(), any());
    verify(wallets, never()).approve(any(), any(), any(), any());
    verify(transfers, never()).updateDecision(any(), any(), any(), any(), anyLong());
    verify(reviews, never()).record(any(), any(), any());
    verify(audit, never()).record(any());
    verify(outbox, never()).enqueue(any(), any(), any());
  }

  // Case 1 -------------------------------------------------------------------------------------

  @Test
  void anApprovalCapturesTheHoldPostsAndCompletesInTheLockOrder() {
    service.approve(REVIEWER, TRANSFER, null);

    InOrder order = inOrder(timeouts, transfers, wallets, reviews, audit, outbox);
    order.verify(timeouts).apply();
    order.verify(transfers).lockHeldForReview(TRANSFER);
    order.verify(wallets).lock(SENDER_WALLET, RECEIVER_WALLET);
    order.verify(transfers).databaseNow();
    // The stored day and the transfer's reference, as the ledger reference.
    order.verify(wallets).approve(locked, AMOUNT, LIMIT_DATE, "TRF-1");
    order.verify(transfers).updateDecision(TRANSFER, "COMPLETED", null, "POSTED", 2L);
    order.verify(reviews).record(eq(TRANSFER), eq(ASSESSMENT), any());
    order.verify(audit).record(any());
    order.verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_SENT, EMAIL_VALUES);
    order.verify(outbox).enqueue(RECEIVER, EmailKind.TRANSFER_RECEIVED, EMAIL_VALUES);
    verify(wallets, never()).release(any(), any(), any());
    assertThat(reviewCase()).contains("STAFF_APPROVAL");
    AuditEntry entry = auditEntry();
    assertThat(entry.action()).isEqualTo("TRANSFER_REVIEW");
    assertThat(entry.actorId()).isEqualTo(REVIEWER);
    assertThat(entry.actorType()).isEqualTo(ActorType.STAFF);
    assertThat(entry.newValue())
        .containsEntry("status", "COMPLETED")
        .containsEntry("action", "APPROVE")
        .containsEntry("decidedBy", "STAFF")
        .containsEntry("requestedAction", "APPROVE")
        .containsEntry("ledgerTransactionId", LEDGER_TRANSACTION.toString())
        .doesNotContainKeys("systemReason", "reasonCategory", "rejectionReasonCategory");
    verify(transactions).commit(any());
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  // Case 3 -------------------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(UnavailableWallet.class)
  void anApprovalThatFindsAWalletUnavailableRejectsWithWalletUnavailable(UnavailableWallet wallet) {
    when(wallets.approve(locked, AMOUNT, LIMIT_DATE, "TRF-1"))
        .thenReturn(new ApprovalOutcome.ReleasedWalletUnavailable(wallet));
    String systemReason = UnavailableWallets.toRisk(wallet).name();

    service.approve(REVIEWER, TRANSFER, null);

    verify(transfers).updateDecision(TRANSFER, "REJECTED", "WALLET_UNAVAILABLE", null, 2L);
    assertThat(reviewCase()).contains("SYSTEM_REJECTION");
    AuditEntry entry = auditEntry();
    assertThat(entry.actorType()).isEqualTo(ActorType.STAFF);
    assertThat(entry.newValue())
        .containsEntry("action", "REJECT")
        .containsEntry("decidedBy", "SYSTEM")
        .containsEntry("requestedAction", "APPROVE")
        .containsEntry("systemReason", systemReason)
        .containsEntry("rejectionReasonCategory", "WALLET_UNAVAILABLE")
        .doesNotContainKeys("reasonCategory", "ledgerTransactionId");
    verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_REJECTED, EMAIL_VALUES);
    verify(outbox, never()).enqueue(eq(RECEIVER), any(), any());
    // The wallet module released the hold itself; no second release here.
    verify(wallets, never()).release(any(), any(), any());
  }

  // Case 2 -------------------------------------------------------------------------------------

  @Test
  void aRejectionReleasesTheHoldAndKeepsTheReasonOutOfAuditAndEmail() {
    service.reject(REVIEWER, TRANSFER, REASON, null);

    InOrder order = inOrder(wallets, transfers, reviews, audit, outbox);
    order.verify(wallets).release(locked, AMOUNT, LIMIT_DATE);
    order.verify(transfers).updateDecision(TRANSFER, "REJECTED", "REVIEW_DECLINED", null, 2L);
    order.verify(reviews).record(eq(TRANSFER), eq(ASSESSMENT), any());
    order.verify(audit).record(any());
    order.verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_REJECTED, EMAIL_VALUES);
    verify(outbox, never()).enqueue(eq(RECEIVER), any(), any());
    assertThat(reviewCase()).contains("STAFF_REJECTION").doesNotContain("secret-7f3e");
    AuditEntry entry = auditEntry();
    assertThat(entry.newValue())
        .containsEntry("action", "REJECT")
        .containsEntry("decidedBy", "STAFF")
        .containsEntry("requestedAction", "REJECT")
        .containsEntry("reasonCategory", "STAFF_REJECTION")
        .containsEntry("rejectionReasonCategory", "REVIEW_DECLINED")
        .doesNotContainKey("systemReason");
    assertThat(entry.newValue().toString()).doesNotContain("secret-7f3e");
    assertThat(entry.oldValue().toString()).doesNotContain("secret-7f3e");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "!!!", "zero​width"})
  void anInvalidReasonBeforeTheDeadlineIs400AndWritesNothing(String reason) {
    assertThatThrownBy(() -> service.reject(REVIEWER, TRANSFER, reason, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.getMessage()).startsWith("reason");
            });

    assertNothingWritten();
    verify(transactions).rollback(any());
  }

  @Test
  void aMissingOrTooLongReasonIs400() {
    assertCode(() -> service.reject(REVIEWER, TRANSFER, null, null), ErrorCode.VALIDATION_ERROR);
    assertCode(
        () -> service.reject(REVIEWER, TRANSFER, "a".repeat(501), null),
        ErrorCode.VALIDATION_ERROR);
    assertNothingWritten();
    // 500 characters are accepted.
    service.reject(REVIEWER, TRANSFER, "a".repeat(500), null);
    verify(transfers).updateDecision(TRANSFER, "REJECTED", "REVIEW_DECLINED", null, 2L);
  }

  // Case 6 -------------------------------------------------------------------------------------

  @Test
  void aLateApprovalExpiresCommitsAndIs409() {
    when(transfers.databaseNow()).thenReturn(DEADLINE);

    assertCode(() -> service.approve(REVIEWER, TRANSFER, null), ErrorCode.INVALID_TRANSFER_STATE);

    verify(wallets, never()).approve(any(), any(), any(), any());
    verify(wallets).release(locked, AMOUNT, LIMIT_DATE);
    verify(transfers).updateDecision(TRANSFER, "EXPIRED", null, null, 2L);
    assertThat(reviewCase()).contains("EXPIRED_ON_LATE_STAFF_ACTION");
    AuditEntry entry = auditEntry();
    assertThat(entry.actorId()).isEqualTo(REVIEWER);
    assertThat(entry.actorType()).isEqualTo(ActorType.STAFF);
    assertThat(entry.newValue())
        .containsEntry("action", "EXPIRE")
        .containsEntry("decidedBy", "SYSTEM")
        .containsEntry("requestedAction", "APPROVE")
        .containsEntry("systemReason", "REVIEW_DEADLINE_PASSED");
    verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_EXPIRED, EMAIL_VALUES);
    verify(transactions).commit(any());
    verify(transactions, never()).rollback(any());
  }

  /** Doc 08 §5: the deadline before the reason; an invalid reason on a late reject expires. */
  @ParameterizedTest
  @ValueSource(strings = {REASON, "", "!!!"})
  void aLateRejectionExpiresWhateverTheReasonAndDropsIt(String reason) {
    when(transfers.databaseNow()).thenReturn(DEADLINE.plusSeconds(5));

    assertCode(
        () -> service.reject(REVIEWER, TRANSFER, reason, null), ErrorCode.INVALID_TRANSFER_STATE);

    verify(transfers).updateDecision(TRANSFER, "EXPIRED", null, null, 2L);
    assertThat(reviewCase()).contains("EXPIRED_ON_LATE_STAFF_ACTION");
    AuditEntry entry = auditEntry();
    assertThat(entry.newValue())
        .containsEntry("requestedAction", "REJECT")
        .containsEntry("systemReason", "REVIEW_DEADLINE_PASSED")
        .doesNotContainKey("reasonCategory");
    assertThat(entry.newValue().toString()).doesNotContain("secret-7f3e");
    verify(transactions).commit(any());
  }

  // Refusals -----------------------------------------------------------------------------------

  @Test
  void aMissingTransferOrOneNeverHeldIsTheIdentical404() {
    when(transfers.lockHeldForReview(TRANSFER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.approve(REVIEWER, TRANSFER, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getMessage()).isEqualTo(ApiException.notFound().getMessage()));
    assertCode(() -> service.reject(REVIEWER, TRANSFER, REASON, null), ErrorCode.NOT_FOUND);
    verify(wallets, never()).lock(any(), any());
    assertNothingWritten();
  }

  @Test
  void theSenderOrTheRecipientCannotReviewIt403AndNothingWritten() {
    for (UUID party : List.of(SENDER, RECEIVER)) {
      assertCode(() -> service.approve(party, TRANSFER, null), ErrorCode.FORBIDDEN);
      assertCode(() -> service.reject(party, TRANSFER, REASON, null), ErrorCode.FORBIDDEN);
    }
    // Even after the deadline: a party never expires it either.
    when(transfers.databaseNow()).thenReturn(DEADLINE.plusSeconds(5));
    assertCode(() -> service.approve(SENDER, TRANSFER, null), ErrorCode.FORBIDDEN);
    verify(wallets, never()).lock(any(), any());
    assertNothingWritten();
  }

  @Test
  void aTransferNoLongerHeldIs409AndWritesNothing() {
    for (String status : List.of("COMPLETED", "CANCELLED", "EXPIRED", "REJECTED")) {
      when(transfers.lockHeldForReview(TRANSFER))
          .thenReturn(Optional.of(row(status, DEADLINE, null, null, 3)));
      assertCode(() -> service.approve(REVIEWER, TRANSFER, null), ErrorCode.INVALID_TRANSFER_STATE);
      assertCode(
          () -> service.reject(REVIEWER, TRANSFER, "!!!", null), ErrorCode.INVALID_TRANSFER_STATE);
    }
    verify(wallets, never()).lock(any(), any());
    assertNothingWritten();
  }

  @Test
  void theGuardsRestrictViolationIs409AndRollsTheCaptureBack() {
    when(transfers.updateDecision(eq(TRANSFER), any(), any(), any(), anyLong()))
        .thenThrow(
            new DataIntegrityViolationException(
                "guard", new SQLException("review has expired", "23001")));

    assertCode(() -> service.approve(REVIEWER, TRANSFER, null), ErrorCode.INVALID_TRANSFER_STATE);

    verify(reviews, never()).record(any(), any(), any());
    verify(audit, never()).record(any());
    verify(outbox, never()).enqueue(any(), any(), any());
    verify(transactions).rollback(any());
    verify(transactions, never()).commit(any());
  }

  @Test
  void aReviewRunsInATransactionOfItsOwnOnly() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.approve(REVIEWER, TRANSFER, null))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> service.reject(REVIEWER, TRANSFER, REASON, null))
        .isInstanceOf(IllegalTransactionStateException.class);
    verify(transactions, never()).getTransaction(any());
    verify(transfers, never()).lockHeldForReview(any());
  }

  @Test
  void theDeciderTakesAReasonOnlyForAStaffRejection() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    for (Request request : List.of(Request.STAFF_APPROVE, Request.CUSTOMER_CANCEL)) {
      assertThatThrownBy(() -> decider.decide(held(), request, REVIEWER, REASON, null))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> decider.decide(held(), Request.STAFF_APPROVE, null, null))
        .isInstanceOf(IllegalStateException.class);
    verify(wallets, never()).lock(any(), any());
  }
}
