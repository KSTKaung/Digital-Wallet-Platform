package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.portfolio.wallet.risk.application.RiskReviewOutcome;
import com.portfolio.wallet.risk.application.RiskReviewService;
import com.portfolio.wallet.transfer.application.HeldTransferDecider.Request;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import com.portfolio.wallet.wallet.application.LockedTransferWallets;
import com.portfolio.wallet.wallet.application.TransferCounterparties;
import com.portfolio.wallet.wallet.application.TransferWalletView;
import com.portfolio.wallet.wallet.application.WalletLookupView;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Rules of {@link TransferCancellationService} and {@link HeldTransferDecider} (documentations/06
 * MVP-ADR-24 "Cancel", "Expiry", review cases 4, 5 and 7; doc 08 §4C.3, §4C.4 criteria 7, 8, 11; §5
 * notes).
 */
class TransferCancellationServiceTest {

  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID RECEIVER = UUID.randomUUID();
  private static final UUID SENDER_WALLET = UUID.randomUUID();
  private static final UUID RECEIVER_WALLET = UUID.randomUUID();
  private static final UUID TRANSFER = UUID.randomUUID();
  private static final UUID ASSESSMENT = UUID.randomUUID();
  private static final Instant HELD_AT = Instant.parse("2026-11-10T05:00:00Z");
  private static final Instant DEADLINE = HELD_AT.plus(Duration.ofHours(24));
  private static final LocalDate LIMIT_DATE = LocalDate.parse("2026-11-10");

  private final TransferRepository transfers = mock(TransferRepository.class);
  private final WalletTransferService wallets = mock(WalletTransferService.class);
  private final TransferCounterparties counterparties = mock(TransferCounterparties.class);
  private final RiskReviewService reviews = mock(RiskReviewService.class);
  private final AuditService audit = mock(AuditService.class);
  private final TransactionTimeouts timeouts = mock(TransactionTimeouts.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final LockedTransferWallets locked = mock(LockedTransferWallets.class);

  private final HeldTransferDecider decider =
      new HeldTransferDecider(transfers, wallets, reviews, audit);
  private final TransferCancellationService service =
      new TransferCancellationService(transfers, decider, counterparties, timeouts, transactions);

  private static TransferRow row(String status, Instant decidedAt, long version) {
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
        null,
        null,
        null,
        version,
        HELD_AT,
        HELD_AT);
  }

  private static TransferWalletView view(UUID wallet, UUID customer, String status) {
    return new TransferWalletView(
        wallet, customer, status, "BASIC", BigDecimal.ZERO, BigDecimal.ZERO, 1, HELD_AT);
  }

  @BeforeEach
  void setUp() {
    // The mock manager marks the transaction active while it runs, as a real one does.
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
    when(transfers.lockSentBy(TRANSFER, SENDER))
        .thenReturn(Optional.of(row("PENDING_REVIEW", null, 2)));
    when(transfers.databaseNow()).thenReturn(DEADLINE.minusSeconds(1));
    when(wallets.lock(SENDER_WALLET, RECEIVER_WALLET)).thenReturn(locked);
    when(locked.sender()).thenReturn(view(SENDER_WALLET, SENDER, "FROZEN"));
    when(transfers.updateDecision(eq(TRANSFER), any(), isNull(), isNull(), eq(2L)))
        .thenAnswer(i -> Optional.of(row(i.getArgument(1), DEADLINE, 3)));
    when(counterparties.of(List.of(RECEIVER_WALLET)))
        .thenReturn(Map.of(RECEIVER_WALLET, new WalletLookupView("Somchai J.", "*******8901")));
  }

  @AfterEach
  void endTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  @Test
  void theSenderCancelsBeforeTheDeadlineInTheLockOrder() {
    TransferView view = service.cancel(SENDER, TRANSFER, null);

    assertThat(view.status()).isEqualTo("CANCELLED");
    assertThat(view.direction()).isEqualTo("SENT");
    assertThat(view.counterparty().maskedWalletNumber()).isEqualTo("*******8901");
    InOrder order = inOrder(timeouts, transfers, wallets, reviews, audit, counterparties);
    order.verify(timeouts).apply();
    order.verify(transfers).lockSentBy(TRANSFER, SENDER);
    order.verify(wallets).lock(SENDER_WALLET, RECEIVER_WALLET);
    // M1: the database time is read after the wallet locks.
    order.verify(transfers).databaseNow();
    // The stored day, even with a frozen sender wallet (MVP-ADR-22, -24).
    order.verify(wallets).release(locked, Money.of("1300.00", Money.THB), LIMIT_DATE);
    order.verify(transfers).updateDecision(TRANSFER, "CANCELLED", null, null, 2L);
    ArgumentCaptor<RiskReviewOutcome> review = ArgumentCaptor.forClass(RiskReviewOutcome.class);
    order.verify(reviews).record(eq(TRANSFER), eq(ASSESSMENT), review.capture());
    assertThat(review.getValue().toString()).contains("CUSTOMER_CANCEL");
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    order.verify(audit).record(entry.capture());
    order.verify(counterparties).of(List.of(RECEIVER_WALLET));
    assertThat(entry.getValue().action()).isEqualTo("TRANSFER_REVIEW");
    assertThat(entry.getValue().actorId()).isEqualTo(SENDER);
    assertThat(entry.getValue().actorType()).isEqualTo(ActorType.CUSTOMER);
    assertThat(entry.getValue().newValue())
        .containsEntry("action", "CANCEL")
        .containsEntry("decidedBy", "CUSTOMER")
        .containsEntry("requestedAction", "CANCEL")
        .containsEntry("status", "CANCELLED")
        .doesNotContainKey("systemReason");
    assertThat(entry.getValue().oldValue()).containsEntry("status", "PENDING_REVIEW");
  }

  /** M2: never inside a caller's transaction, which could swallow the committed expiry. */
  @Test
  void theCancelRunsInATransactionOfItsOwnOnly() {
    service.cancel(SENDER, TRANSFER, null);
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    org.mockito.Mockito.clearInvocations(transactions, transfers);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.cancel(SENDER, TRANSFER, null))
        .isInstanceOf(IllegalTransactionStateException.class);
    verify(transactions, never()).getTransaction(any());
    verify(transfers, never()).lockSentBy(any(), any());
  }

  @Test
  void atTheDeadlineTheCancelExpiresTheTransferAndIs409AfterTheCommit() {
    when(transfers.databaseNow()).thenReturn(DEADLINE);

    assertCode(() -> service.cancel(SENDER, TRANSFER, null), ErrorCode.INVALID_TRANSFER_STATE);

    verify(wallets).release(locked, Money.of("1300.00", Money.THB), LIMIT_DATE);
    verify(transfers).updateDecision(TRANSFER, "EXPIRED", null, null, 2L);
    ArgumentCaptor<RiskReviewOutcome> review = ArgumentCaptor.forClass(RiskReviewOutcome.class);
    verify(reviews).record(eq(TRANSFER), eq(ASSESSMENT), review.capture());
    assertThat(review.getValue().toString()).contains("EXPIRED_ON_LATE_CANCEL");
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().newValue())
        .containsEntry("action", "EXPIRE")
        .containsEntry("decidedBy", "SYSTEM")
        .containsEntry("requestedAction", "CANCEL")
        .containsEntry("systemReason", "REVIEW_DEADLINE_PASSED");
    // The expiry committed: the transaction was not rolled back.
    verify(transactions).commit(any());
    verify(transactions, never()).rollback(any());
  }

  @Test
  void anyoneButTheSenderAndAMissingTransferGetTheIdentical404WithoutALock() {
    for (UUID caller : List.of(RECEIVER, UUID.randomUUID())) {
      assertThatThrownBy(() -> service.cancel(caller, TRANSFER, null))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.getMessage()).isEqualTo(ApiException.notFound().getMessage()));
    }
    when(transfers.lockSentBy(TRANSFER, SENDER)).thenReturn(Optional.empty());
    assertCode(() -> service.cancel(SENDER, TRANSFER, null), ErrorCode.NOT_FOUND);
    // Ownership is part of the locking statement: no plain lock of the row is ever taken.
    verify(transfers, never()).lockForUpdate(any(), any());
    verify(wallets, never()).lock(any(), any());
    verify(reviews, never()).record(any(), any(), any());
  }

  @Test
  void aTransferNoLongerHeldIs409AndWritesNothing() {
    for (String status : List.of("COMPLETED", "CANCELLED", "EXPIRED", "REJECTED", "BLOCKED")) {
      when(transfers.lockSentBy(TRANSFER, SENDER))
          .thenReturn(Optional.of(row(status, "COMPLETED".equals(status) ? null : DEADLINE, 3)));
      assertCode(() -> service.cancel(SENDER, TRANSFER, null), ErrorCode.INVALID_TRANSFER_STATE);
    }
    verify(wallets, never()).lock(any(), any());
    verify(wallets, never()).release(any(), any(), any());
    verify(reviews, never()).record(any(), any(), any());
    verify(audit, never()).record(any());
  }

  @Test
  void theGuardsRestrictViolationIs409AndRollsBack() {
    when(transfers.updateDecision(eq(TRANSFER), any(), isNull(), isNull(), anyLong()))
        .thenThrow(
            new DataIntegrityViolationException(
                "guard", new SQLException("review has expired", "23001")));

    assertCode(() -> service.cancel(SENDER, TRANSFER, null), ErrorCode.INVALID_TRANSFER_STATE);

    verify(reviews, never()).record(any(), any(), any());
    verify(audit, never()).record(any());
    verify(transactions).rollback(any());
  }

  @Test
  void anotherDatabaseFailureIsNotTurnedInto409() {
    UncategorizedSQLException timeout =
        new UncategorizedSQLException("lock", "sql", new SQLException("timeout", "55P03"));
    when(transfers.updateDecision(eq(TRANSFER), any(), isNull(), isNull(), anyLong()))
        .thenThrow(timeout);

    assertThatThrownBy(() -> service.cancel(SENDER, TRANSFER, null)).isSameAs(timeout);
  }

  @Test
  void aVersionMismatchOrAForeignLockedSenderIsAProgrammingError() {
    when(transfers.updateDecision(eq(TRANSFER), any(), isNull(), isNull(), anyLong()))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.cancel(SENDER, TRANSFER, null))
        .isInstanceOf(IllegalStateException.class);

    when(locked.sender()).thenReturn(view(SENDER_WALLET, UUID.randomUUID(), "ACTIVE"));
    assertThatThrownBy(() -> service.cancel(SENDER, TRANSFER, null))
        .isInstanceOf(IllegalStateException.class);
    when(locked.sender()).thenReturn(view(UUID.randomUUID(), SENDER, "ACTIVE"));
    assertThatThrownBy(() -> service.cancel(SENDER, TRANSFER, null))
        .isInstanceOf(IllegalStateException.class);
  }

  // The shared sequence: the expiry job's case (step 6) and the preconditions ------------------

  @Test
  void theExpiryJobExpiresADueTransferAsTheSystemWithoutAnActor() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    when(transfers.databaseNow()).thenReturn(DEADLINE.plusSeconds(1));

    HeldTransferDecider.Decision decision =
        decider.decide(row("PENDING_REVIEW", null, 2), Request.EXPIRY_JOB, null, null);

    assertThat(decision.expired()).isTrue();
    assertThat(decision.after().status()).isEqualTo("EXPIRED");
    verify(wallets).release(locked, Money.of("1300.00", Money.THB), LIMIT_DATE);
    ArgumentCaptor<RiskReviewOutcome> review = ArgumentCaptor.forClass(RiskReviewOutcome.class);
    verify(reviews).record(eq(TRANSFER), eq(ASSESSMENT), review.capture());
    assertThat(review.getValue().toString()).contains("EXPIRED_BY_JOB");
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().actorId()).isNull();
    assertThat(entry.getValue().actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(entry.getValue().newValue())
        .containsEntry("action", "EXPIRE")
        .containsEntry("decidedBy", "SYSTEM")
        .containsEntry("systemReason", "REVIEW_DEADLINE_PASSED")
        .doesNotContainKey("requestedAction");
  }

  @Test
  void theDeciderRefusesWhatItsPreconditionsExclude() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransferRow held = row("PENDING_REVIEW", null, 2);
    // The job never decides a transfer before its deadline.
    assertThatThrownBy(() -> decider.decide(held, Request.EXPIRY_JOB, null, null))
        .isInstanceOf(IllegalStateException.class);
    // Only the job acts without an actor, and the job has none.
    assertThatThrownBy(() -> decider.decide(held, Request.CUSTOMER_CANCEL, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> decider.decide(held, Request.EXPIRY_JOB, SENDER, null))
        .isInstanceOf(IllegalStateException.class);
    // Only a transfer found held under its lock.
    assertThatThrownBy(
            () ->
                decider.decide(
                    row("CANCELLED", DEADLINE, 3), Request.CUSTOMER_CANCEL, SENDER, null))
        .isInstanceOf(IllegalStateException.class);
    // Only inside a transaction.
    TransactionSynchronizationManager.setActualTransactionActive(false);
    assertThatThrownBy(() -> decider.decide(held, Request.CUSTOMER_CANCEL, SENDER, null))
        .isInstanceOf(IllegalStateException.class);
    verify(wallets, never()).release(any(), any(), any());
    verify(reviews, never()).record(any(), any(), any());
  }

  @Test
  void restrictViolationIsFoundAnywhereInTheCauses() {
    SQLException guard = new SQLException("x", "23001");
    assertThat(HeldTransferDecider.isRestrictViolation(guard)).isTrue();
    assertThat(
            HeldTransferDecider.isRestrictViolation(
                new RuntimeException(new IllegalStateException(guard))))
        .isTrue();
    assertThat(HeldTransferDecider.isRestrictViolation(new SQLException("x", "23505"))).isFalse();
    assertThat(HeldTransferDecider.isRestrictViolation(new RuntimeException("x"))).isFalse();
  }
}
