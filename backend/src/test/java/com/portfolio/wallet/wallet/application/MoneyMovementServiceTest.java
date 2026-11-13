package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.common.time.BusinessTimeProperties;
import com.portfolio.wallet.ledger.application.LedgerPostingService;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.wallet.domain.LimitPolicy;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.OperationLimits;
import com.portfolio.wallet.wallet.domain.RequestFingerprint;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRepository;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRow;
import com.portfolio.wallet.wallet.persistence.MoneyMovementRepository;
import com.portfolio.wallet.wallet.persistence.MoneyMovementRepository.Table;
import com.portfolio.wallet.wallet.persistence.MoneyMovementRow;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rules and order of {@link MoneyMovementService} (documentations/06 MVP-ADR-03, -18, -21, -22; doc
 * 07 §8; doc 08 §4B.2 Q14). The idempotency service is replaced by one that runs the movement
 * directly; the database behaviour is in the integration tests.
 */
class MoneyMovementServiceTest {

  // 16:59:59Z is still 2026-11-04 in Bangkok; 17:00:00Z is 2026-11-05 (MVP-ADR-22).
  private static final Instant NOW = Instant.parse("2026-11-04T16:59:59Z");
  private static final LocalDate BANGKOK_DAY = LocalDate.parse("2026-11-04");
  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID WALLET = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private static final UUID LEDGER_TX = UUID.randomUUID();
  private static final String NUMBER = new WalletNumberGenerator().next().value();

  private final WalletRepository wallets = mock(WalletRepository.class);
  private final DailyLimitUsageRepository usages = mock(DailyLimitUsageRepository.class);
  private final MoneyMovementRepository movements = mock(MoneyMovementRepository.class);
  private final LedgerPostingService ledger = mock(LedgerPostingService.class);
  private final AuditService audit = mock(AuditService.class);
  private final IdempotencyService idempotency = mock(IdempotencyService.class);
  private final JsonMapper json = JsonMapper.builder().build();
  private final NotificationOutbox outbox = mock(NotificationOutbox.class);

  private MoneyMovementService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    Map<Operation, OperationLimits> limits = new EnumMap<>(Operation.class);
    limits.put(Operation.DEPOSIT, limits("100.00", "100000.00", "200000.00"));
    limits.put(Operation.WITHDRAWAL, limits("100.00", "30000.00", "50000.00"));
    limits.put(Operation.TRANSFER, limits("1.00", "50000.00", "100000.00"));
    service =
        new MoneyMovementService(
            wallets,
            usages,
            movements,
            ledger,
            audit,
            idempotency,
            new LimitPolicy(limits),
            new BusinessTimeProperties(ZoneId.of("Asia/Bangkok")),
            Clock.fixed(NOW, ZoneOffset.UTC),
            json,
            outbox);
    // Runs the movement; a stored refusal runs the hook, as the real service does after storing it.
    when(idempotency.execute(any(), any(), any()))
        .thenAnswer(
            i -> {
              try {
                return new IdempotentResult(
                    ((Supplier<StoredResponse>) i.getArgument(1)).get(), false);
              } catch (ApiException e) {
                if (IdempotencyService.STORED_REJECTIONS.contains(e.errorCode())) {
                  ((java.util.function.Consumer<ApiException>) i.getArgument(2)).accept(e);
                }
                throw e;
              }
            });
    walletIs("ACTIVE", "1000.00");
    when(usages.lockOrCreate(eq(WALLET), anyString(), eq(BANGKOK_DAY)))
        .thenReturn(new DailyLimitUsageRow(new BigDecimal("0.00"), new BigDecimal("0.00"), 3));
    when(wallets.updateAvailableBalance(eq(WALLET), any(), eq(7L))).thenReturn(1);
    when(usages.updateUsed(eq(WALLET), anyString(), eq(BANGKOK_DAY), any(), eq(3L))).thenReturn(1);
    when(ledger.postDeposit(eq(WALLET), anyString(), any())).thenReturn(LEDGER_TX);
    when(ledger.postWithdrawal(eq(WALLET), anyString(), any())).thenReturn(LEDGER_TX);
    when(movements.findByCustomerAndKey(any(), eq(CUSTOMER), eq(KEY))).thenReturn(Optional.empty());
  }

  private static OperationLimits limits(String min, String max, String daily) {
    return OperationLimits.sameForEveryTier(money(min), money(max), money(daily));
  }

  private static Money money(String amount) {
    return Money.of(new BigDecimal(amount), Money.THB);
  }

  private void walletIs(String status, String available) {
    WalletRow row =
        new WalletRow(
            WALLET,
            NUMBER,
            CUSTOMER,
            "BASIC",
            status,
            "THB",
            new BigDecimal(available),
            new BigDecimal("0.00"),
            7,
            NOW,
            NOW);
    when(wallets.findByCustomer(CUSTOMER)).thenReturn(Optional.of(row));
    when(wallets.findByCustomerForUpdate(CUSTOMER)).thenReturn(Optional.of(row));
  }

  private void usedToday(String used) {
    when(usages.lockOrCreate(eq(WALLET), anyString(), eq(BANGKOK_DAY)))
        .thenReturn(new DailyLimitUsageRow(new BigDecimal(used), new BigDecimal("0.00"), 3));
  }

  private MoneyMovementView deposit(String amount) {
    return service.deposit(CUSTOMER, KEY, new BigDecimal(amount), "THB", "salary", null);
  }

  private MoneyMovementView withdraw(String amount) {
    return service.withdraw(CUSTOMER, KEY, new BigDecimal(amount), "THB", null);
  }

  private static void assertRejected(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  // Success ------------------------------------------------------------------------------------

  @Test
  void aDepositMovesMoneyInTheDocumentedOrder() {
    MoneyMovementView view = deposit("250.5");

    assertThat(view.walletNumber()).isEqualTo(NUMBER);
    assertThat(view.amount()).isEqualByComparingTo("250.50");
    assertThat(view.amount().scale()).isEqualTo(2);
    assertThat(view.status()).isEqualTo("COMPLETED");
    assertThat(view.ledgerStatus()).isEqualTo("POSTED");
    assertThat(view.reference()).matches("DEP-[0-9A-F]{32}");

    InOrder order = inOrder(movements, wallets, usages, ledger, audit, outbox);
    order.verify(movements).findByCustomerAndKey(Table.DEPOSITS, CUSTOMER, KEY);
    order.verify(wallets).findByCustomerForUpdate(CUSTOMER);
    order.verify(usages).lockOrCreate(WALLET, "DEPOSIT", BANGKOK_DAY);
    order.verify(wallets).updateAvailableBalance(WALLET, new BigDecimal("1250.50"), 7);
    order.verify(usages).updateUsed(WALLET, "DEPOSIT", BANGKOK_DAY, new BigDecimal("250.50"), 3);
    order.verify(ledger).postDeposit(WALLET, view.reference(), money("250.50"));
    ArgumentCaptor<MoneyMovementRow> row = ArgumentCaptor.forClass(MoneyMovementRow.class);
    order.verify(movements).insert(eq(Table.DEPOSITS), row.capture(), eq("salary"));
    order.verify(audit).record(any(AuditEntry.class));
    // Q9: the customer's email in the same transaction; never the note or the balance.
    order
        .verify(outbox)
        .enqueue(
            CUSTOMER,
            EmailKind.DEPOSIT_COMPLETED,
            Map.of("amount", "250.50", "currency", "THB", "reference", view.reference()));

    assertThat(row.getValue().id()).isEqualTo(view.id());
    assertThat(row.getValue().idempotencyKey()).isEqualTo(KEY);
    assertThat(row.getValue().limitDate()).isEqualTo(BANGKOK_DAY);
    assertThat(row.getValue().reference()).isEqualTo(view.reference());
  }

  @Test
  void aWithdrawalDebitsAndPostsTheReverseAndHasNoNote() {
    MoneyMovementView view = withdraw("1000.00");

    assertThat(view.reference()).startsWith("WDR-");
    verify(wallets).updateAvailableBalance(WALLET, new BigDecimal("0.00"), 7);
    verify(ledger).postWithdrawal(WALLET, view.reference(), money("1000"));
    verify(movements).insert(eq(Table.WITHDRAWALS), any(), eq(null));
    verify(outbox)
        .enqueue(
            CUSTOMER,
            EmailKind.WITHDRAWAL_COMPLETED,
            Map.of("amount", "1000.00", "currency", "THB", "reference", view.reference()));
  }

  @Test
  void theAuditRowNamesTheMovementButNotTheNote() {
    deposit("100");

    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().action()).isEqualTo("DEPOSIT");
    assertThat(entry.getValue().actorId()).isEqualTo(CUSTOMER);
    assertThat(entry.getValue().newValue())
        .containsEntry("amount", "100.00")
        .containsEntry("availableBalance", "1100.00")
        .containsEntry("ledgerTransactionId", LEDGER_TX.toString())
        .doesNotContainValue("salary");
    assertThat(entry.getValue().oldValue()).containsEntry("availableBalance", "1000.00");
  }

  @Test
  void theDepositNoteIsPartOfTheRequestHashAndTheWithdrawalHasNone() {
    ArgumentCaptor<IdempotentRequest> request = ArgumentCaptor.forClass(IdempotentRequest.class);

    deposit("100");
    service.deposit(CUSTOMER, KEY, new BigDecimal("100"), "THB", "other", null);
    withdraw("100");
    verify(idempotency, org.mockito.Mockito.times(3)).execute(request.capture(), any(), any());

    RequestFingerprint first = request.getAllValues().get(0).fingerprint();
    RequestFingerprint second = request.getAllValues().get(1).fingerprint();
    RequestFingerprint third = request.getAllValues().get(2).fingerprint();
    assertThat(first.sha256Hex()).isNotEqualTo(second.sha256Hex());
    assertThat(third.operation()).isEqualTo(Operation.WITHDRAWAL);
    assertThat(request.getAllValues().get(0).key()).isEqualTo(KEY);
  }

  @Test
  void trailingZerosAreAccepted() {
    assertThat(deposit("100.0000").amount()).isEqualByComparingTo("100.00");
  }

  // Before the key ----------------------------------------------------------------------------

  @Test
  void withoutAWalletItIsKycRequiredAndTheKeyIsNotUsed() {
    when(wallets.findByCustomer(CUSTOMER)).thenReturn(Optional.empty());

    assertRejected(() -> deposit("100"), ErrorCode.KYC_REQUIRED);
    verifyNoInteractions(idempotency);
  }

  @Test
  void aThirdDecimalIsInvalidAmountNotRounded() {
    assertRejected(() -> deposit("100.001"), ErrorCode.INVALID_AMOUNT);
    verifyNoInteractions(idempotency);
  }

  @Test
  void zeroAndNegativeAmountsAreInvalid() {
    assertRejected(() -> deposit("0.00"), ErrorCode.INVALID_AMOUNT);
    assertRejected(() -> withdraw("-1.00"), ErrorCode.INVALID_AMOUNT);
    verifyNoInteractions(idempotency);
  }

  /** Y2: the amount is checked before the currency (doc 02 §2.3 step order). */
  @Test
  void theAmountIsCheckedBeforeTheCurrency() {
    assertThatThrownBy(
            () -> service.deposit(CUSTOMER, KEY, new BigDecimal("0.00"), "USD", null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_AMOUNT);
              assertThat(e.getMessage()).isEqualTo(MoneyMovementService.NOT_POSITIVE);
            });
    assertRejected(
        () -> service.withdraw(CUSTOMER, KEY, new BigDecimal("1.001"), "USD", null),
        ErrorCode.INVALID_AMOUNT);
    verifyNoInteractions(idempotency);
  }

  /** Y3: over the NUMERIC(19,2) magnitude is "too large", told apart from "too many decimals". */
  @Test
  void anAmountTooLargeHasItsOwnMessage() {
    for (String amount : new String[] {"123456789012345678", "100000000000000000.00", "1E+30"}) {
      assertThatThrownBy(
              () -> service.deposit(CUSTOMER, KEY, new BigDecimal(amount), "THB", null, null))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_AMOUNT);
                assertThat(e.getMessage()).isEqualTo(MoneyMovementService.TOO_LARGE);
              });
      assertThatThrownBy(() -> service.withdraw(CUSTOMER, KEY, new BigDecimal(amount), "THB", null))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.getMessage()).isEqualTo(MoneyMovementService.TOO_LARGE));
    }
    assertThatThrownBy(
            () -> service.deposit(CUSTOMER, KEY, new BigDecimal("1.001"), "THB", null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getMessage()).isEqualTo(MoneyMovementService.TOO_MANY_DECIMALS));
    // The largest amount NUMERIC(19,2) holds is not "too large"; it fails the limits instead.
    assertRejected(
        () ->
            service.deposit(
                CUSTOMER, KEY, new BigDecimal("99999999999999999.99"), "THB", null, null),
        ErrorCode.SINGLE_LIMIT_EXCEEDED);
  }

  @Test
  void onlyThbIsAccepted() {
    for (String currency : new String[] {"USD", "thb", ""}) {
      assertRejected(
          () -> service.deposit(CUSTOMER, KEY, new BigDecimal("100"), currency, null, null),
          ErrorCode.CURRENCY_MISMATCH);
    }
    verifyNoInteractions(idempotency);
  }

  // Under the locks ----------------------------------------------------------------------------

  @Test
  void aFrozenWalletIsRefusedBeforeLimitsAndBalance() {
    walletIs("FROZEN", "0.00");

    assertRejected(() -> withdraw("99999"), ErrorCode.WALLET_FROZEN);
    verifyNoInteractions(usages, ledger);
    // Only the refusal's own audit row (W5).
    verify(audit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                entry ->
                    entry.result() == com.portfolio.wallet.audit.application.AuditResult.FAILURE));
  }

  @Test
  void limitsUseTheConfiguredValues() {
    assertRejected(() -> deposit("99.99"), ErrorCode.INVALID_AMOUNT);
    assertRejected(() -> deposit("100000.01"), ErrorCode.SINGLE_LIMIT_EXCEEDED);
    assertRejected(() -> withdraw("30000.01"), ErrorCode.SINGLE_LIMIT_EXCEEDED);
    verifyNoInteractions(ledger);
  }

  @Test
  void theDailyLimitCountsTheDaysUsage() {
    usedToday("199900.00");
    assertThat(deposit("100.00").status()).isEqualTo("COMPLETED");
    verify(usages).updateUsed(WALLET, "DEPOSIT", BANGKOK_DAY, new BigDecimal("200000.00"), 3);

    usedToday("199900.01");
    assertRejected(() -> deposit("100.00"), ErrorCode.DAILY_LIMIT_EXCEEDED);
  }

  @Test
  void aWithdrawalAboveTheAvailableBalanceIsInsufficientBalance() {
    walletIs("ACTIVE", "999.99");

    assertRejected(() -> withdraw("1000.00"), ErrorCode.INSUFFICIENT_BALANCE);
    verify(wallets, never()).updateAvailableBalance(any(), any(), anyLong());
    verifyNoInteractions(ledger, outbox);
  }

  @Test
  void aStoredRefusalIsAuditedAsAFailureWithoutTheNote() {
    walletIs("ACTIVE", "10.00");

    assertRejected(
        () -> service.withdraw(CUSTOMER, KEY, new BigDecimal("500"), "THB", null),
        ErrorCode.INSUFFICIENT_BALANCE);
    usedToday("199999.00");
    assertRejected(() -> deposit("100"), ErrorCode.DAILY_LIMIT_EXCEEDED);

    ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit, org.mockito.Mockito.times(2)).record(entries.capture());
    AuditEntry withdrawal = entries.getAllValues().get(0);
    assertThat(withdrawal.action()).isEqualTo("WITHDRAWAL");
    assertThat(withdrawal.result())
        .isEqualTo(com.portfolio.wallet.audit.application.AuditResult.FAILURE);
    assertThat(withdrawal.actorId()).isEqualTo(CUSTOMER);
    assertThat(withdrawal.newValue())
        .containsEntry("walletId", WALLET.toString())
        .containsEntry("amount", "500.00")
        .containsEntry("currency", "THB")
        .containsEntry("errorCode", "INSUFFICIENT_BALANCE");
    assertThat(entries.getAllValues().get(1).newValue())
        .containsEntry("errorCode", "DAILY_LIMIT_EXCEEDED")
        .doesNotContainValue("salary");
    verifyNoInteractions(outbox);
  }

  @Test
  void refusalsThatAreNotStoredAreNotAuditedHere() {
    assertRejected(() -> deposit("99.99"), ErrorCode.INVALID_AMOUNT);
    when(wallets.findByCustomer(CUSTOMER)).thenReturn(Optional.empty());
    assertRejected(() -> deposit("100"), ErrorCode.KYC_REQUIRED);
    verifyNoInteractions(audit);
  }

  @Test
  void aVersionMismatchUnderTheLockIsAServerError() {
    when(wallets.updateAvailableBalance(eq(WALLET), any(), eq(7L))).thenReturn(0);
    assertThatThrownBy(() -> deposit("100")).isInstanceOf(IllegalStateException.class);

    when(wallets.updateAvailableBalance(eq(WALLET), any(), eq(7L))).thenReturn(1);
    when(usages.updateUsed(eq(WALLET), anyString(), eq(BANGKOK_DAY), any(), eq(3L))).thenReturn(0);
    assertThatThrownBy(() -> deposit("100")).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(ledger);
  }

  @Test
  void aWalletThatDisappearsUnderItsLockIsAServerError() {
    when(wallets.findByCustomerForUpdate(CUSTOMER)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> deposit("100")).isInstanceOf(IllegalStateException.class);
  }

  // Q14 ----------------------------------------------------------------------------------------

  private void storedMovement(String amount, UUID walletId) {
    when(movements.findByCustomerAndKey(Table.WITHDRAWALS, CUSTOMER, KEY))
        .thenReturn(
            Optional.of(
                new MoneyMovementRow(
                    UUID.randomUUID(),
                    walletId,
                    CUSTOMER,
                    new BigDecimal(amount),
                    "THB",
                    "COMPLETED",
                    KEY,
                    "WDR-STORED",
                    BANGKOK_DAY,
                    "POSTED",
                    null)));
  }

  @Test
  void afterPurgeTheSameMovementIsReturnedWithoutMovingMoney() {
    storedMovement("500.00", WALLET);

    MoneyMovementView view = withdraw("500");

    assertThat(view.reference()).isEqualTo("WDR-STORED");
    assertThat(view.walletNumber()).isEqualTo(NUMBER);
    verify(wallets, never()).findByCustomerForUpdate(any());
    verifyNoInteractions(ledger, audit, usages, outbox);
  }

  @Test
  void afterPurgeAnotherAmountOrWalletIsKeyReused() {
    storedMovement("500.01", WALLET);
    assertRejected(() -> withdraw("500"), ErrorCode.IDEMPOTENCY_KEY_REUSED);

    storedMovement("500.00", UUID.randomUUID());
    assertRejected(() -> withdraw("500"), ErrorCode.IDEMPOTENCY_KEY_REUSED);
    verifyNoInteractions(ledger);
  }
}
