package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.idempotency.IdempotentResult;
import com.portfolio.wallet.common.idempotency.StoredResponse;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.risk.application.AssessmentDecision;
import com.portfolio.wallet.risk.application.RiskAssessmentRequest;
import com.portfolio.wallet.risk.application.RiskAssessmentResult;
import com.portfolio.wallet.risk.application.RiskAssessmentService;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRepository.NewTransfer;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import com.portfolio.wallet.wallet.application.LockedTransferWallets;
import com.portfolio.wallet.wallet.application.TransferPrecheck;
import com.portfolio.wallet.wallet.application.TransferRecipient;
import com.portfolio.wallet.wallet.application.TransferSettlement;
import com.portfolio.wallet.wallet.application.TransferWalletView;
import com.portfolio.wallet.wallet.application.WalletLookupService;
import com.portfolio.wallet.wallet.application.WalletTransferService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Q3 check order of {@link TransferService} (documentations/08 §4C.2 Q3, Q4, Q5; §4C.4 criteria
 * 1, 3, 4, 9; §5 notes): each refusal ends the request before the later steps, the pre-check
 * refusal goes through the engine, any risk failure is {@code SYSTEM_ERROR} with an independent
 * audit row, and each decision ends in its outcome.
 */
class TransferServiceTest {

  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID SENDER_WALLET = UUID.randomUUID();
  private static final UUID RECEIVER = UUID.randomUUID();
  private static final UUID RECEIVER_WALLET = UUID.randomUUID();
  private static final UUID ASSESSMENT = UUID.randomUUID();
  private static final UUID LEDGER_TX = UUID.randomUUID();
  private static final String NUMBER = "12345678901";
  private static final String IP = "192.0.2.7";
  private static final Instant CREATED = Instant.parse("2026-11-11T05:00:00Z");
  private static final Instant WALLET_CREATED = Instant.parse("2026-11-01T05:00:00Z");
  private static final LocalDate PRECHECK_DAY = LocalDate.parse("2026-11-11");
  private static final LocalDate LOCK_DAY = LocalDate.parse("2026-11-12");

  private final WalletLookupService recipients = mock(WalletLookupService.class);
  private final WalletTransferService wallets = mock(WalletTransferService.class);
  private final TransferIdempotency idempotency = mock(TransferIdempotency.class);
  private final RiskAssessmentService risk = mock(RiskAssessmentService.class);
  private final TransferRepository transfers = mock(TransferRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final JsonMapper json = JsonMapper.builder().build();
  private final LockedTransferWallets locked = mock(LockedTransferWallets.class);
  private final NotificationOutbox outbox = mock(NotificationOutbox.class);

  private final TransferService service =
      new TransferService(
          recipients,
          wallets,
          idempotency,
          risk,
          transfers,
          audit,
          new TransferReviewProperties(Duration.ofHours(24)),
          json,
          new TransferEmails(outbox));

  private static TransferWalletView view(UUID wallet, UUID customer) {
    return new TransferWalletView(
        wallet,
        customer,
        "ACTIVE",
        "BASIC",
        new BigDecimal("1000.00"),
        BigDecimal.ZERO.setScale(2),
        3,
        WALLET_CREATED);
  }

  private static TransferCommand command(String amount, String currency) {
    return new TransferCommand(
        SENDER, UUID.randomUUID(), NUMBER, new BigDecimal(amount), currency, "rent", IP);
  }

  private static TransferCommand command() {
    return command("100.00", "THB");
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    when(recipients.resolveTransferRecipient(SENDER, NUMBER, IP))
        .thenReturn(new TransferRecipient(RECEIVER_WALLET, RECEIVER, "Somchai J.", "*******8901"));
    when(idempotency.replayIfCompleted(any())).thenReturn(Optional.empty());
    when(idempotency.storedRejections())
        .thenReturn(
            EnumSet.of(
                ErrorCode.INSUFFICIENT_BALANCE,
                ErrorCode.SINGLE_LIMIT_EXCEEDED,
                ErrorCode.DAILY_LIMIT_EXCEEDED,
                ErrorCode.WALLET_FROZEN,
                ErrorCode.RISK_BLOCKED));
    when(wallets.precheck(eq(SENDER), any()))
        .thenReturn(new TransferPrecheck(view(SENDER_WALLET, SENDER), PRECHECK_DAY));
    when(wallets.walletIdOf(SENDER)).thenReturn(SENDER_WALLET);
    when(wallets.lock(SENDER_WALLET, RECEIVER_WALLET)).thenReturn(locked);
    when(locked.sender()).thenReturn(view(SENDER_WALLET, SENDER));
    when(locked.recipient()).thenReturn(view(RECEIVER_WALLET, RECEIVER));
    when(wallets.complete(eq(locked), any(), any()))
        .thenReturn(new TransferSettlement(LOCK_DAY, LEDGER_TX));
    when(wallets.hold(eq(locked), any())).thenReturn(LOCK_DAY);
    when(transfers.insert(any(), anyLong())).thenAnswer(i -> stored(i.getArgument(0)));
    // The engine: runs the money transaction, or stores a refusal and runs its hook.
    when(idempotency.execute(any(), any(), any()))
        .thenAnswer(
            i -> {
              Supplier<StoredResponse> movement = i.getArgument(1);
              try {
                return new IdempotentResult(movement.get(), false);
              } catch (ApiException e) {
                ((Consumer<ApiException>) i.getArgument(2)).accept(e);
                throw e;
              }
            });
    when(idempotency.refuse(any(), any(), any()))
        .thenAnswer(
            i -> {
              ApiException refusal = i.getArgument(1);
              ((Consumer<ApiException>) i.getArgument(2)).accept(refusal);
              throw refusal;
            });
  }

  private void decision(AssessmentDecision decision) {
    when(risk.assess(any())).thenReturn(new RiskAssessmentResult(ASSESSMENT, decision));
  }

  /** The row the database returns for an insert, with its own times. */
  private static TransferRow stored(NewTransfer t) {
    boolean held = "PENDING_REVIEW".equals(t.status());
    return new TransferRow(
        t.id(),
        t.reference(),
        t.senderCustomerId(),
        t.receiverCustomerId(),
        t.senderWalletId(),
        t.receiverWalletId(),
        t.amount(),
        t.currency(),
        t.description(),
        t.status(),
        t.idempotencyKey(),
        t.limitDate(),
        t.riskAssessmentId(),
        held ? CREATED : null,
        held ? CREATED.plus(Duration.ofHours(24)) : null,
        null,
        null,
        t.ledgerStatus(),
        t.correlationId(),
        0,
        CREATED,
        CREATED);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  // Step 1: amount and currency -----------------------------------------------------------------

  @Test
  void theAmountIsCheckedBeforeTheCurrencyAndBeforeAnythingElse() {
    assertCode(() -> service.create(command("1.001", "USD")), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> service.create(command("0.00", "THB")), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> service.create(command("-1", "THB")), ErrorCode.INVALID_AMOUNT);
    assertCode(
        () -> service.create(command("12345678901234567890", "THB")), ErrorCode.INVALID_AMOUNT);
    assertCode(() -> service.create(command("1.00", "USD")), ErrorCode.CURRENCY_MISMATCH);
    assertCode(() -> service.create(command("1.00", null)), ErrorCode.CURRENCY_MISMATCH);
    verifyNoInteractions(recipients, idempotency, wallets, risk, transfers, audit);
  }

  @Test
  void requestedAmountAcceptsTrailingZerosAndNeverRounds() {
    assertThat(TransferService.requestedAmount(new BigDecimal("1.0000"), "THB").amount())
        .isEqualByComparingTo("1.00");
    assertThat(TransferService.requestedAmount(new BigDecimal("1.0000"), "THB").amount().scale())
        .isEqualTo(2);
    assertThatThrownBy(() -> TransferService.requestedAmount(new BigDecimal("1.005"), "THB"))
        .hasMessage("amount: must have at most 2 decimal places");
    assertThat(TransferService.newReference()).startsWith("TRF-").hasSize(36);
  }

  // Steps 2 and 3: KYC and the recipient --------------------------------------------------------

  @Test
  void aRecipientRefusalEndsTheRequestBeforeTheKeyIsUsed() {
    for (ApiException refusal :
        new ApiException[] {
          new ApiException(ErrorCode.KYC_REQUIRED, "kyc"),
          ApiException.notFound(),
          new ApiException(ErrorCode.SELF_TRANSFER, "self")
        }) {
      doThrow(refusal).when(recipients).resolveTransferRecipient(SENDER, NUMBER, IP);
      assertCode(() -> service.create(command()), refusal.errorCode());
    }
    verifyNoInteractions(idempotency, wallets, risk, transfers, audit);
  }

  @Test
  void aResolutionThatReturnsTheSenderIsAProgrammingError() {
    when(recipients.resolveTransferRecipient(SENDER, NUMBER, IP))
        .thenReturn(new TransferRecipient(SENDER_WALLET, SENDER, "x", "y"));
    assertThatThrownBy(() -> service.create(command())).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(idempotency, risk);
  }

  // Step 4: the early replay --------------------------------------------------------------------

  @Test
  void aReplayIsAnsweredBeforeThePrecheckAndTheAssessment() {
    TransferView earlier =
        new TransferView(
            UUID.randomUUID(),
            "TRF-1",
            "COMPLETED",
            "SENT",
            new BigDecimal("100.00"),
            "THB",
            new CounterpartyView("Somchai J.", "*******8901"),
            null,
            null,
            CREATED,
            "POSTED");
    when(idempotency.replayIfCompleted(any()))
        .thenReturn(
            Optional.of(
                new IdempotentResult(
                    new StoredResponse(201, json.writeValueAsString(earlier)), true)));

    assertThat(service.create(command())).isEqualTo(earlier);
    verify(wallets, never()).precheck(any(), any());
    verifyNoInteractions(risk, transfers, audit);
    verify(idempotency, never()).execute(any(), any(), any());
  }

  @Test
  void aReusedKeyEndsTheRequestBeforeThePrecheck() {
    when(idempotency.replayIfCompleted(any()))
        .thenThrow(new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "reused"));
    assertCode(() -> service.create(command()), ErrorCode.IDEMPOTENCY_KEY_REUSED);
    verify(wallets, never()).precheck(any(), any());
    verifyNoInteractions(risk);
  }

  @Test
  void theFingerprintIsTheSendersAndHashesTheValuesAsReceived() {
    decision(AssessmentDecision.ALLOW);
    service.create(command());
    ArgumentCaptor<TransferIdempotentRequest> request =
        ArgumentCaptor.forClass(TransferIdempotentRequest.class);
    verify(idempotency).replayIfCompleted(request.capture());
    assertThat(request.getValue().fingerprint().customerId()).isEqualTo(SENDER);
    assertThat(request.getValue().fingerprint().sha256Hex())
        .isEqualTo(
            com.portfolio.wallet.transfer.domain.TransferRequestFingerprint.builder(
                    SENDER, NUMBER, Money.of("100.00", Money.THB))
                .description("rent")
                .build()
                .sha256Hex());
  }

  // Step 5: the pre-check -----------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(
      value = ErrorCode.class,
      names = {
        "INSUFFICIENT_BALANCE",
        "SINGLE_LIMIT_EXCEEDED",
        "DAILY_LIMIT_EXCEEDED",
        "WALLET_FROZEN"
      })
  void aStorablePrecheckRefusalGoesThroughTheEngineWithItsAuditRow(ErrorCode code) {
    ApiException refusal = new ApiException(code, "refused");
    when(wallets.precheck(eq(SENDER), any())).thenThrow(refusal);

    assertThatThrownBy(() -> service.create(command())).isSameAs(refusal);

    verify(idempotency).refuse(any(), eq(refusal), any());
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().action()).isEqualTo("TRANSFER");
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.FAILURE);
    assertThat(entry.getValue().resourceId()).isNull();
    assertThat(entry.getValue().newValue())
        .containsEntry("errorCode", code.name())
        .containsEntry("senderWalletId", SENDER_WALLET.toString())
        .containsEntry("amount", "100.00")
        .doesNotContainKey("description");
    verifyNoInteractions(risk);
    // Only the key's guard read: before the engine, and first in the storing transaction.
    verify(transfers, times(2)).findBySenderAndKey(eq(SENDER), any());
    verify(transfers, never()).insert(any(), anyLong());
    verify(transfers, never()).completedTransferExists(any(), any());
  }

  @Test
  void theMinimumIsRethrownAndNotStored() {
    ApiException below = new ApiException(ErrorCode.INVALID_AMOUNT, "amount: is below the minimum");
    when(wallets.precheck(eq(SENDER), any())).thenThrow(below);

    assertThatThrownBy(() -> service.create(command())).isSameAs(below);
    verify(idempotency, never()).refuse(any(), any(), any());
    verifyNoInteractions(risk, audit, outbox);
    verify(transfers, never()).insert(any(), anyLong());
  }

  @Test
  void aPrecheckRefusalReplaysAnEarlierSuccessWithTheSameKey() {
    when(wallets.precheck(eq(SENDER), any()))
        .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "x"));
    TransferView earlier =
        new TransferView(
            UUID.randomUUID(),
            "TRF-2",
            "PENDING_REVIEW",
            "SENT",
            BigDecimal.TEN,
            "THB",
            new CounterpartyView("a", "b"),
            null,
            CREATED,
            CREATED,
            null);
    org.mockito.Mockito.doReturn(
            new IdempotentResult(new StoredResponse(201, json.writeValueAsString(earlier)), true))
        .when(idempotency)
        .refuse(any(), any(), any());

    assertThat(service.create(command())).isEqualTo(earlier);
    verifyNoInteractions(risk);
  }

  // Step 6: the risk assessment -----------------------------------------------------------------

  @Test
  void theAssessmentGetsTheSubjectTheIpTheWalletAgeAndTheRecipientFact() {
    decision(AssessmentDecision.ALLOW);
    when(transfers.completedTransferExists(SENDER, RECEIVER)).thenReturn(true);

    TransferView created = service.create(command());

    ArgumentCaptor<RiskAssessmentRequest> request =
        ArgumentCaptor.forClass(RiskAssessmentRequest.class);
    verify(risk).assess(request.capture());
    assertThat(request.getValue().senderCustomerId()).isEqualTo(SENDER);
    assertThat(request.getValue().senderWalletCreatedAt()).isEqualTo(WALLET_CREATED);
    assertThat(request.getValue().completedTransferToRecipientExists()).isTrue();
    assertThat(request.getValue().clientIp().getHostAddress()).isEqualTo(IP);
    assertThat(request.getValue().amount()).isEqualTo(Money.of("100.00", Money.THB));
    // The pre-generated id is the transfer's id.
    assertThat(created.id()).isEqualTo(request.getValue().transferId());
    InOrder order = inOrder(recipients, idempotency, wallets, risk);
    order.verify(recipients).resolveTransferRecipient(SENDER, NUMBER, IP);
    order.verify(idempotency).replayIfCompleted(any());
    order.verify(wallets).precheck(eq(SENDER), any());
    order.verify(risk).assess(any());
    order.verify(idempotency).execute(any(), any(), any());
  }

  @Test
  void anyRiskFailureIsSystemErrorWithAnIndependentAuditRowAndNothingElse() {
    for (Throwable failure :
        new Throwable[] {
          new ApiException(ErrorCode.SYSTEM_ERROR, "x"),
          new IllegalStateException("x"),
          new AssertionError("x")
        }) {
      org.mockito.Mockito.reset(risk, audit);
      when(risk.assess(any())).thenThrow(failure);

      assertThatThrownBy(() -> service.create(command()))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR);
                assertThat(e.getMessage()).isEqualTo("An unexpected error occurred");
              });
      ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
      verify(audit).recordIndependently(entry.capture());
      verify(audit, never()).record(any());
      assertThat(entry.getValue().result()).isEqualTo(AuditResult.FAILURE);
      assertThat(entry.getValue().newValue()).containsEntry("errorCode", "SYSTEM_ERROR");
      assertThat(entry.getValue().resourceId()).isNotNull();
    }
    verify(idempotency, never()).execute(any(), any(), any());
    verify(idempotency, never()).refuse(any(), any(), any());
    verify(wallets, never()).lock(any(), any());
    verify(transfers, never()).insert(any(), anyLong());
  }

  @Test
  void aFailingRecipientFactOrAFailingAuditIsStillSystemError() {
    when(transfers.completedTransferExists(SENDER, RECEIVER))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
    doThrow(new IllegalStateException("audit down")).when(audit).recordIndependently(any());

    assertCode(() -> service.create(command()), ErrorCode.SYSTEM_ERROR);
    verifyNoInteractions(risk);
    verify(idempotency, never()).execute(any(), any(), any());
  }

  // Step 7: the outcomes ------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(
      value = AssessmentDecision.class,
      names = {"ALLOW", "MONITOR"})
  void allowAndMonitorCompleteWithThePostingAndTheDayUnderTheLocks(AssessmentDecision decision) {
    decision(decision);

    TransferView created = service.create(command());

    assertThat(created.status()).isEqualTo("COMPLETED");
    assertThat(created.ledgerStatus()).isEqualTo("POSTED");
    assertThat(created.direction()).isEqualTo("SENT");
    assertThat(created.counterparty().maskedName()).isEqualTo("Somchai J.");
    ArgumentCaptor<NewTransfer> row = ArgumentCaptor.forClass(NewTransfer.class);
    verify(transfers).insert(row.capture(), eq(86_400L));
    assertThat(row.getValue().status()).isEqualTo("COMPLETED");
    assertThat(row.getValue().ledgerStatus()).isEqualTo("POSTED");
    assertThat(row.getValue().limitDate()).isEqualTo(LOCK_DAY);
    assertThat(row.getValue().riskAssessmentId()).isEqualTo(ASSESSMENT);
    assertThat(row.getValue().description()).isEqualTo("rent");
    InOrder order = inOrder(wallets, transfers, audit, outbox);
    order.verify(wallets).lock(SENDER_WALLET, RECEIVER_WALLET);
    order.verify(wallets).complete(eq(locked), any(), eq(created.reference()));
    order.verify(transfers).insert(any(), anyLong());
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    order.verify(audit).record(entry.capture());
    // Q9: both parties, in the money transaction, amount, currency and reference only.
    Map<String, String> values =
        Map.of("amount", "100.00", "currency", "THB", "reference", created.reference());
    order.verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_SENT, values);
    order.verify(outbox).enqueue(RECEIVER, EmailKind.TRANSFER_RECEIVED, values);
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.SUCCESS);
    assertThat(entry.getValue().newValue())
        .containsEntry("ledgerTransactionId", LEDGER_TX.toString())
        .doesNotContainKey("description");
    verify(wallets, never()).hold(any(), any());
  }

  @Test
  void manualReviewHoldsAndStoresTheHoldsDay() {
    decision(AssessmentDecision.MANUAL_REVIEW);

    TransferView created = service.create(command());

    assertThat(created.status()).isEqualTo("PENDING_REVIEW");
    assertThat(created.ledgerStatus()).isNull();
    assertThat(created.reviewExpiresAt()).isEqualTo(CREATED.plus(Duration.ofHours(24)));
    ArgumentCaptor<NewTransfer> row = ArgumentCaptor.forClass(NewTransfer.class);
    verify(transfers).insert(row.capture(), eq(86_400L));
    assertThat(row.getValue().status()).isEqualTo("PENDING_REVIEW");
    assertThat(row.getValue().limitDate()).isEqualTo(LOCK_DAY);
    assertThat(row.getValue().ledgerStatus()).isNull();
    verify(wallets).hold(eq(locked), any());
    verify(wallets, never()).complete(any(), any(), any());
    // Q9: the sender only; the receiver hears nothing before completion.
    verify(outbox)
        .enqueue(
            SENDER,
            EmailKind.TRANSFER_HELD,
            Map.of("amount", "100.00", "currency", "THB", "reference", created.reference()));
    verify(outbox, never()).enqueue(eq(RECEIVER), any(), any());
  }

  @Test
  void blockStoresRiskBlockedWithTheBlockedRowOnThePrechecksDay() {
    decision(AssessmentDecision.BLOCK);

    assertThatThrownBy(() -> service.create(command()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RISK_BLOCKED);
              assertThat(e.getMessage()).isEqualTo("The transfer was refused");
            });

    ArgumentCaptor<NewTransfer> row = ArgumentCaptor.forClass(NewTransfer.class);
    verify(transfers).insert(row.capture(), anyLong());
    assertThat(row.getValue().status()).isEqualTo("BLOCKED");
    assertThat(row.getValue().limitDate()).isEqualTo(PRECHECK_DAY);
    assertThat(row.getValue().ledgerStatus()).isNull();
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.FAILURE);
    assertThat(entry.getValue().newValue()).containsEntry("errorCode", "RISK_BLOCKED");
    verify(wallets, never()).lock(any(), any());
    verify(idempotency, never()).execute(any(), any(), any());
    // Q9: no email for a block.
    verifyNoInteractions(outbox);
  }

  @Test
  void aRefusalUnderTheLocksIsAuditedWithTheTransferId() {
    decision(AssessmentDecision.ALLOW);
    when(wallets.complete(eq(locked), any(), any()))
        .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "x"));

    assertCode(() -> service.create(command()), ErrorCode.INSUFFICIENT_BALANCE);

    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().resourceId()).isNotNull();
    assertThat(entry.getValue().newValue()).containsEntry("errorCode", "INSUFFICIENT_BALANCE");
    verify(transfers, never()).insert(any(), anyLong());
    verifyNoInteractions(outbox);
  }

  @Test
  void lockedWalletsOfOtherPartiesAreAProgrammingError() {
    decision(AssessmentDecision.ALLOW);
    when(locked.sender()).thenReturn(view(SENDER_WALLET, UUID.randomUUID()));
    assertThatThrownBy(() -> service.create(command())).isInstanceOf(IllegalStateException.class);

    when(locked.sender()).thenReturn(view(SENDER_WALLET, SENDER));
    when(locked.recipient()).thenReturn(view(RECEIVER_WALLET, UUID.randomUUID()));
    assertThatThrownBy(() -> service.create(command())).isInstanceOf(IllegalStateException.class);
    verify(wallets, never()).complete(any(), any(), any());
  }

  // Q14 for transfers (owner, 2026-11-11, option a) ---------------------------------------------

  private static final UUID BOUND_ID = UUID.randomUUID();
  private static final Instant BOUND_CREATED = Instant.parse("2026-11-09T05:00:00Z");

  /** A transfer the sender created earlier with the key, as the database stores it. */
  private static TransferRow bound(
      String status, boolean held, UUID senderWallet, UUID receiverWallet, String amount) {
    boolean completed = "COMPLETED".equals(status);
    boolean decided = held && !"PENDING_REVIEW".equals(status);
    return new TransferRow(
        BOUND_ID,
        "TRF-BOUND",
        SENDER,
        RECEIVER,
        senderWallet,
        receiverWallet,
        new BigDecimal(amount),
        "THB",
        "an older note",
        status,
        UUID.randomUUID(),
        PRECHECK_DAY,
        ASSESSMENT,
        held ? BOUND_CREATED : null,
        held ? BOUND_CREATED.plus(Duration.ofHours(24)) : null,
        decided ? BOUND_CREATED.plusSeconds(60) : null,
        "REJECTED".equals(status) ? "REVIEW_DECLINED" : null,
        completed ? "POSTED" : null,
        null,
        decided ? 1 : 0,
        BOUND_CREATED,
        BOUND_CREATED);
  }

  private static TransferRow bound(String status, boolean held) {
    return bound(status, held, SENDER_WALLET, RECEIVER_WALLET, "100.00");
  }

  private void keyUsedBy(TransferRow row) {
    when(transfers.findBySenderAndKey(eq(SENDER), any())).thenReturn(Optional.of(row));
  }

  /** Nothing ran after the guard: no pre-check, assessment, engine, lock, row or audit. */
  private void assertNothingRanAfterTheGuard() {
    verify(wallets, never()).precheck(any(), any());
    verifyNoInteractions(risk, audit, outbox);
    verify(idempotency, never()).execute(any(), any(), any());
    verify(idempotency, never()).refuse(any(), any(), any());
    verify(wallets, never()).lock(any(), any());
    verify(transfers, never()).insert(any(), anyLong());
  }

  @Test
  void aCompletedTransferWithTheKeyAnswersWithItsCreationResponse() {
    keyUsedBy(bound("COMPLETED", false));

    // A different description is not compared (Z3), and 100 equals 100.00.
    TransferView answer =
        service.create(
            new TransferCommand(
                SENDER, UUID.randomUUID(), NUMBER, new BigDecimal("100"), "THB", "other", IP));

    assertThat(answer)
        .isEqualTo(
            new TransferView(
                BOUND_ID,
                "TRF-BOUND",
                "COMPLETED",
                "SENT",
                new BigDecimal("100.00"),
                "THB",
                new CounterpartyView("Somchai J.", "*******8901"),
                null,
                null,
                BOUND_CREATED,
                "POSTED"));
    assertNothingRanAfterTheGuard();
    InOrder order = inOrder(recipients, idempotency, transfers);
    order.verify(recipients).resolveTransferRecipient(SENDER, NUMBER, IP);
    order.verify(idempotency).replayIfCompleted(any());
    order.verify(transfers).findBySenderAndKey(eq(SENDER), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"PENDING_REVIEW", "COMPLETED", "REJECTED", "CANCELLED", "EXPIRED"})
  void aHeldTransferAnswersAsItWasCreatedWhateverItsReviewDecided(String status) {
    keyUsedBy(bound(status, true));

    TransferView answer = service.create(command());

    assertThat(answer.status()).isEqualTo("PENDING_REVIEW");
    assertThat(answer.ledgerStatus()).isNull();
    assertThat(answer.rejectionReason()).isNull();
    assertThat(answer.reviewExpiresAt()).isEqualTo(BOUND_CREATED.plus(Duration.ofHours(24)));
    assertThat(answer.id()).isEqualTo(BOUND_ID);
    assertNothingRanAfterTheGuard();
  }

  @Test
  void aBlockedTransferWithTheKeyAnswersRiskBlockedAgain() {
    keyUsedBy(bound("BLOCKED", false));

    assertThatThrownBy(() -> service.create(command()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RISK_BLOCKED);
              assertThat(e.getMessage()).isEqualTo("The transfer was refused");
            });
    assertNothingRanAfterTheGuard();
  }

  @Test
  void anotherTransferWithTheKeyIsKeyReused() {
    UUID otherWallet = UUID.randomUUID();
    for (TransferRow row :
        new TransferRow[] {
          bound("COMPLETED", false, SENDER_WALLET, RECEIVER_WALLET, "100.01"),
          bound("COMPLETED", false, SENDER_WALLET, otherWallet, "100.00"),
          bound("PENDING_REVIEW", true, otherWallet, RECEIVER_WALLET, "100.00"),
          bound("BLOCKED", false, SENDER_WALLET, RECEIVER_WALLET, "99.99")
        }) {
      keyUsedBy(row);
      assertThatThrownBy(() -> service.create(command()))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                assertThat(e.getMessage())
                    .isEqualTo("Idempotency-Key was already used for a different request");
              });
    }
    assertNothingRanAfterTheGuard();
  }

  @Test
  void aTransferThatAppearsBeforeTheMoneyTransactionEndsItWithoutAWrite() {
    decision(AssessmentDecision.ALLOW);
    // Absent at the early check, committed by the time the engine's transaction looks.
    when(transfers.findBySenderAndKey(eq(SENDER), any()))
        .thenReturn(Optional.empty(), Optional.of(bound("COMPLETED", false)));

    assertThat(service.create(command()).id()).isEqualTo(BOUND_ID);

    verify(idempotency).execute(any(), any(), any());
    verify(wallets, never()).lock(any(), any());
    verify(transfers, never()).insert(any(), anyLong());
    verify(audit, never()).record(any());
  }

  @Test
  void aTransferThatAppearsBeforeABlockIsStoredAnswersInsteadOfASecondRow() {
    decision(AssessmentDecision.BLOCK);
    when(transfers.findBySenderAndKey(eq(SENDER), any()))
        .thenReturn(
            Optional.empty(),
            Optional.of(bound("COMPLETED", false, SENDER_WALLET, RECEIVER_WALLET, "5.00")));

    assertCode(() -> service.create(command()), ErrorCode.IDEMPOTENCY_KEY_REUSED);

    verify(transfers, never()).insert(any(), anyLong());
    verify(audit, never()).record(any());
  }

  @Test
  void aTransferThatAppearsBeforeAPrecheckRefusalIsStoredAnswersInstead() {
    when(wallets.precheck(eq(SENDER), any()))
        .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "x"));
    when(transfers.findBySenderAndKey(eq(SENDER), any()))
        .thenReturn(Optional.empty(), Optional.of(bound("BLOCKED", false)));

    assertCode(() -> service.create(command()), ErrorCode.RISK_BLOCKED);

    verify(audit, never()).record(any());
    verifyNoInteractions(risk);
  }

  @Test
  void aTransferThatAppearsBeforeARefusalUnderTheLocksIsStoredAnswersInstead() {
    decision(AssessmentDecision.ALLOW);
    when(wallets.complete(eq(locked), any(), any()))
        .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "x"));
    when(transfers.findBySenderAndKey(eq(SENDER), any()))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(bound("PENDING_REVIEW", true)));

    assertThat(service.create(command()).status()).isEqualTo("PENDING_REVIEW");

    verify(audit, never()).record(any());
    verify(transfers, never()).insert(any(), anyLong());
  }

  @Test
  void theViewAsCreatedIsOnlyForTheSenderAndNeverForABlock() {
    CounterpartyView counterparty = new CounterpartyView("a", "b");
    assertThatThrownBy(
            () -> TransferViews.asCreated(bound("COMPLETED", false), RECEIVER, counterparty))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> TransferViews.asCreated(bound("BLOCKED", false), SENDER, counterparty))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theCommandPrintsNothingFromTheRequest() {
    TransferCommand command = command();
    assertThat(command.toString())
        .doesNotContain(NUMBER)
        .doesNotContain("rent")
        .doesNotContain(command.idempotencyKey().toString());
  }
}
