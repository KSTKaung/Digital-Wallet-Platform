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

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.common.time.BusinessTimeProperties;
import com.portfolio.wallet.ledger.application.LedgerPostingService;
import com.portfolio.wallet.wallet.domain.LimitPolicy;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.OperationLimits;
import com.portfolio.wallet.wallet.domain.Tier;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRepository;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRow;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Rules, order and writes of {@link WalletTransferService} (documentations/06 MVP-ADR-03, -08, -18,
 * -20, -22, -24; doc 07 §8; doc 08 §4C.2 Q3, §4C.4 criteria 2, 5, 6). The repositories are mocks;
 * locks, rollback and the ledger triggers are proved in {@code WalletTransferServiceIT}.
 */
class WalletTransferServiceTest {

  // 16:59:59Z is still 2026-11-09 in Bangkok; 17:00:00Z is 2026-11-10 (MVP-ADR-22).
  private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-11-09T16:59:59Z");
  private static final Instant AFTER_MIDNIGHT = Instant.parse("2026-11-09T17:00:00Z");
  private static final LocalDate DAY = LocalDate.parse("2026-11-09");
  private static final LocalDate NEXT_DAY = LocalDate.parse("2026-11-10");
  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID SENDER_CUSTOMER = UUID.randomUUID();
  private static final UUID RECIPIENT = UUID.randomUUID();
  private static final UUID RECIPIENT_CUSTOMER = UUID.randomUUID();
  private static final UUID LEDGER_TX = UUID.randomUUID();
  private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456Z");
  private static final String REFERENCE = "TRF-1";
  private static final String TRANSFER = "TRANSFER";

  private final WalletRepository wallets = mock(WalletRepository.class);
  private final DailyLimitUsageRepository usages = mock(DailyLimitUsageRepository.class);
  private final LedgerPostingService ledger = mock(LedgerPostingService.class);
  private final TransactionTimeouts timeouts = mock(TransactionTimeouts.class);

  private WalletTransferService service;

  @BeforeEach
  void setUp() {
    // What the money transaction provides: synchronization, and the D14 timeouts applied.
    TransactionSynchronizationManager.initSynchronization();
    when(timeouts.applied()).thenReturn(true);
    service = at(BEFORE_MIDNIGHT);
    when(wallets.updateBalances(any(), any(), any(), anyLong())).thenReturn(1);
    when(usages.updateUsedAndReserved(any(), anyString(), any(), any(), any(), anyLong()))
        .thenReturn(1);
    when(ledger.postTransfer(any(), any(), anyString(), any())).thenReturn(LEDGER_TX);
    usage(DAY, "0.00", "0.00");
  }

  @AfterEach
  void endTransaction() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private WalletTransferService at(Instant now) {
    return new WalletTransferService(
        wallets,
        usages,
        ledger,
        policy(),
        new BusinessTimeProperties(ZoneId.of("Asia/Bangkok")),
        Clock.fixed(now, ZoneOffset.UTC),
        timeouts);
  }

  /** The MVP-ADR-18 values, as configured in application.yaml. */
  private static LimitPolicy policy() {
    Map<Operation, OperationLimits> limits = new EnumMap<>(Operation.class);
    limits.put(
        Operation.DEPOSIT,
        OperationLimits.sameForEveryTier(m("100.00"), m("100000.00"), m("200000.00")));
    limits.put(
        Operation.WITHDRAWAL,
        OperationLimits.sameForEveryTier(m("100.00"), m("30000.00"), m("50000.00")));
    Map<Tier, Money> daily = new EnumMap<>(Tier.class);
    daily.put(Tier.BASIC, m("100000.00"));
    daily.put(Tier.VERIFIED, m("500000.00"));
    limits.put(Operation.TRANSFER, new OperationLimits(m("1.00"), m("50000.00"), daily));
    return new LimitPolicy(limits);
  }

  private static Money m(String amount) {
    return Money.of(amount, Money.THB);
  }

  private static BigDecimal d(String amount) {
    return new BigDecimal(amount);
  }

  private static WalletRow row(
      UUID id, UUID customer, String tier, String status, String available, String reserved) {
    return new WalletRow(
        id,
        new WalletNumberGenerator().next().value(),
        customer,
        tier,
        status,
        "THB",
        d(available),
        d(reserved),
        7,
        CREATED,
        CREATED);
  }

  private static WalletRow sender(String status, String available, String reserved) {
    return row(SENDER, SENDER_CUSTOMER, "BASIC", status, available, reserved);
  }

  private static WalletRow recipient(String status, String available) {
    return row(RECIPIENT, RECIPIENT_CUSTOMER, "VERIFIED", status, available, "0.00");
  }

  private void usage(LocalDate date, String used, String reserved) {
    DailyLimitUsageRow usage = new DailyLimitUsageRow(d(used), d(reserved), 3);
    when(usages.lockOrCreate(SENDER, TRANSFER, date)).thenReturn(usage);
    when(usages.lockExisting(SENDER, TRANSFER, date)).thenReturn(Optional.of(usage));
    when(usages.find(SENDER, TRANSFER, date)).thenReturn(Optional.of(usage));
  }

  private LockedTransferWallets locked(WalletRow sender, WalletRow recipient) {
    // The database returns them in its own uuid order; the service matches them by id.
    when(wallets.lockBothForUpdate(SENDER, RECIPIENT)).thenReturn(List.of(recipient, sender));
    return service.lock(SENDER, RECIPIENT);
  }

  private static void assertRefused(Runnable call, ErrorCode code, String message) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(code);
              assertThat(e.getMessage()).isEqualTo(message);
            });
  }

  private void verifyNothingWritten() {
    verify(wallets, never()).updateBalances(any(), any(), any(), anyLong());
    verify(usages, never())
        .updateUsedAndReserved(any(), anyString(), any(), any(), any(), anyLong());
    verifyNoInteractions(ledger);
  }

  // Pre-check (Q3 step 5) ------------------------------------------------------------------------

  @Test
  void thePrecheckReturnsTheSenderWithItsDatabaseCreationTime() {
    when(wallets.findByCustomer(SENDER_CUSTOMER))
        .thenReturn(Optional.of(sender("ACTIVE", "500.00", "20.00")));

    TransferPrecheck result = service.precheck(SENDER_CUSTOMER, m("500.00"));
    TransferWalletView view = result.sender();
    assertThat(result.limitDate()).isEqualTo(DAY);

    assertThat(view.walletId()).isEqualTo(SENDER);
    assertThat(view.customerId()).isEqualTo(SENDER_CUSTOMER);
    assertThat(view.createdAt()).isEqualTo(CREATED);
    assertThat(view.status()).isEqualTo("ACTIVE");
    assertThat(view.active()).isTrue();
    assertThat(view.tier()).isEqualTo("BASIC");
    assertThat(view.availableBalance()).isEqualByComparingTo("500.00");
    assertThat(view.reservedBalance()).isEqualByComparingTo("20.00");
    assertThat(view.version()).isEqualTo(7);
    assertThat(view.toString()).doesNotContain(SENDER.toString()).doesNotContain("500");
    verify(usages).find(SENDER, TRANSFER, DAY);
    verify(usages, never()).lockOrCreate(any(), anyString(), any());
    verifyNothingWritten();
  }

  @Test
  void thePrecheckWithoutAWalletIsKycRequired() {
    when(wallets.findByCustomer(SENDER_CUSTOMER)).thenReturn(Optional.empty());

    assertRefused(
        () -> service.precheck(SENDER_CUSTOMER, m("1.00")),
        ErrorCode.KYC_REQUIRED,
        MoneyMovementService.KYC_REQUIRED);
  }

  @ParameterizedTest(name = "{0} {1}, used {2} + reserved {3} -> {4}")
  @CsvSource({
    "ACTIVE, 0.99, 0.00, 0.00, INVALID_AMOUNT",
    "ACTIVE, 1.00, 0.00, 0.00, ",
    "ACTIVE, 1.01, 0.00, 0.00, ",
    "ACTIVE, 49999.99, 0.00, 0.00, ",
    "ACTIVE, 50000.00, 0.00, 0.00, ",
    "ACTIVE, 50000.01, 0.00, 0.00, SINGLE_LIMIT_EXCEEDED",
    "ACTIVE, 10000.00, 60000.00, 30000.00, ",
    "ACTIVE, 10000.01, 60000.00, 30000.00, DAILY_LIMIT_EXCEEDED",
    "ACTIVE, 60000.01, 0.00, 0.00, SINGLE_LIMIT_EXCEEDED",
    "ACTIVE, 70000.01, 0.00, 0.00, SINGLE_LIMIT_EXCEEDED",
    "ACTIVE, 70000.00, 0.00, 0.00, SINGLE_LIMIT_EXCEEDED",
    "FROZEN, 0.99, 100000.00, 0.00, WALLET_FROZEN"
  })
  void thePrecheckAppliesEveryRuleOnItsBoundary(
      String status, String amount, String used, String reserved, ErrorCode expected) {
    when(wallets.findByCustomer(SENDER_CUSTOMER))
        .thenReturn(Optional.of(sender(status, "70000.00", "0.00")));
    usage(DAY, used, reserved);

    if (expected == null) {
      assertThat(service.precheck(SENDER_CUSTOMER, m(amount)).sender().walletId())
          .isEqualTo(SENDER);
    } else {
      assertThatThrownBy(() -> service.precheck(SENDER_CUSTOMER, m(amount)))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
  }

  @Test
  void thePrecheckRefusesTheBalanceAfterTheLimitsWithTheMovementMessages() {
    when(wallets.findByCustomer(SENDER_CUSTOMER))
        .thenReturn(Optional.of(sender("ACTIVE", "99.99", "1000.00")));

    assertRefused(
        () -> service.precheck(SENDER_CUSTOMER, m("100.00")),
        ErrorCode.INSUFFICIENT_BALANCE,
        MoneyMovementService.INSUFFICIENT_BALANCE);
    usage(DAY, "99999.00", "1.00");
    assertRefused(
        () -> service.precheck(SENDER_CUSTOMER, m("100.00")),
        ErrorCode.DAILY_LIMIT_EXCEEDED,
        MoneyMovementService.DAILY_LIMIT);
    assertRefused(
        () -> service.precheck(SENDER_CUSTOMER, m("50000.01")),
        ErrorCode.SINGLE_LIMIT_EXCEEDED,
        MoneyMovementService.SINGLE_LIMIT);
    assertRefused(
        () -> service.precheck(SENDER_CUSTOMER, m("0.99")),
        ErrorCode.INVALID_AMOUNT,
        MoneyMovementService.BELOW_MINIMUM);
  }

  @Test
  void aClosedSenderIsAServerErrorNotABusinessRefusal() {
    when(wallets.findByCustomer(SENDER_CUSTOMER))
        .thenReturn(Optional.of(sender("CLOSED", "500.00", "0.00")));

    assertThatThrownBy(() -> service.precheck(SENDER_CUSTOMER, m("1.00")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(WalletTransferService.CLOSED);
  }

  /** MVP-ADR-22: the day's usage is the Bangkok day; midnight there is 17:00 UTC. */
  @Test
  void thePrecheckCountsTheBangkokDayOfTheClock() {
    when(wallets.findByCustomer(SENDER_CUSTOMER))
        .thenReturn(Optional.of(sender("ACTIVE", "500000.00", "0.00")));
    usage(DAY, "100000.00", "0.00");
    when(usages.find(SENDER, TRANSFER, NEXT_DAY)).thenReturn(Optional.empty());

    assertRefused(
        () -> service.precheck(SENDER_CUSTOMER, m("1.00")),
        ErrorCode.DAILY_LIMIT_EXCEEDED,
        MoneyMovementService.DAILY_LIMIT);
    // One second later it is a new Bangkok day with no usage row yet: counted as zero.
    TransferPrecheck afterMidnight = at(AFTER_MIDNIGHT).precheck(SENDER_CUSTOMER, m("50000.00"));
    assertThat(afterMidnight.sender().walletId()).isEqualTo(SENDER);
    assertThat(afterMidnight.limitDate()).isEqualTo(NEXT_DAY);
    verify(usages).find(SENDER, TRANSFER, NEXT_DAY);
  }

  // Lock -----------------------------------------------------------------------------------------

  @Test
  void theLockMatchesTheRowsByIdWhateverTheirOrder() {
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "10.00", "0.00"), recipient("FROZEN", "5.00"));

    assertThat(locked.sender().walletId()).isEqualTo(SENDER);
    assertThat(locked.recipient().walletId()).isEqualTo(RECIPIENT);
    assertThat(locked.recipient().active()).isFalse();
    assertThat(locked.toString()).doesNotContain(SENDER.toString()).doesNotContain("10.00");
    verify(wallets).lockBothForUpdate(SENDER, RECIPIENT);
  }

  @Test
  void theLockNeedsTwoDifferentExistingWallets() {
    assertThatThrownBy(() -> service.lock(SENDER, SENDER))
        .isInstanceOf(IllegalArgumentException.class);
    when(wallets.lockBothForUpdate(SENDER, RECIPIENT))
        .thenReturn(List.of(sender("ACTIVE", "1.00", "0.00")));
    assertThatThrownBy(() -> service.lock(SENDER, RECIPIENT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.lock(null, RECIPIENT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.lock(SENDER, null)).isInstanceOf(NullPointerException.class);
  }

  // Complete -------------------------------------------------------------------------------------

  @Test
  void aCompletionMovesTheMoneyInTheLockOrderAndPostsTheLedger() {
    usage(DAY, "100.00", "50.00");
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "50.00"), recipient("ACTIVE", "5.00"));

    TransferSettlement settlement = service.complete(locked, m("250.50"), REFERENCE);

    assertThat(settlement.limitDate()).isEqualTo(DAY);
    assertThat(settlement.ledgerTransactionId()).isEqualTo(LEDGER_TX);
    InOrder order = inOrder(wallets, usages, ledger);
    order.verify(wallets).lockBothForUpdate(SENDER, RECIPIENT);
    order.verify(usages).lockOrCreate(SENDER, TRANSFER, DAY);
    order.verify(wallets).updateBalances(SENDER, d("749.50"), d("50.00"), 7);
    order.verify(wallets).updateBalances(RECIPIENT, d("255.50"), d("0.00"), 7);
    order.verify(usages).updateUsedAndReserved(SENDER, TRANSFER, DAY, d("350.50"), d("50.00"), 3);
    order.verify(ledger).postTransfer(SENDER, RECIPIENT, REFERENCE, m("250.50"));
  }

  @Test
  void aRecipientThatIsNoLongerActiveIsTheIdenticalNotFound() {
    for (String status : new String[] {"FROZEN", "CLOSED"}) {
      LockedTransferWallets forCompletion =
          locked(sender("ACTIVE", "1000.00", "0.00"), recipient(status, "0.00"));
      assertThatThrownBy(() -> service.complete(forCompletion, m("10.00"), REFERENCE))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(e.getMessage()).isEqualTo(ApiException.notFound().getMessage());
              });
      LockedTransferWallets forHold =
          locked(sender("ACTIVE", "1000.00", "0.00"), recipient(status, "0.00"));
      assertThatThrownBy(() -> service.hold(forHold, m("10.00")))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
    // Checked before the sender, as in Q3 (recipient at step 3, sender at step 5).
    LockedTransferWallets bothFrozen =
        locked(sender("FROZEN", "1000.00", "0.00"), recipient("FROZEN", "0.00"));
    assertThatThrownBy(() -> service.complete(bothFrozen, m("10.00"), REFERENCE))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    verify(usages, never()).lockOrCreate(any(), anyString(), any());
    verifyNothingWritten();
  }

  @ParameterizedTest(name = "{0} {1}, used {2} + reserved {3}, available {4} -> {5}")
  @CsvSource({
    "FROZEN, 10.00, 0.00, 0.00, 1000.00, WALLET_FROZEN",
    "ACTIVE, 0.99, 0.00, 0.00, 1000.00, INVALID_AMOUNT",
    "ACTIVE, 50000.01, 0.00, 0.00, 60000.00, SINGLE_LIMIT_EXCEEDED",
    "ACTIVE, 0.01, 0.00, 0.00, 60000.00, INVALID_AMOUNT",
    "ACTIVE, 1.00, 99999.00, 0.00, 60000.00, ",
    "ACTIVE, 1.01, 99999.00, 0.00, 60000.00, DAILY_LIMIT_EXCEEDED",
    "ACTIVE, 1.00, 0.00, 99999.01, 60000.00, DAILY_LIMIT_EXCEEDED",
    "ACTIVE, 100.00, 0.00, 0.00, 100.00, ",
    "ACTIVE, 100.01, 0.00, 0.00, 100.00, INSUFFICIENT_BALANCE"
  })
  void underTheLocksEveryRuleIsCheckedAgainForBothOperations(
      String status,
      String amount,
      String used,
      String reserved,
      String available,
      ErrorCode expected) {
    usage(DAY, used, reserved);
    for (boolean hold : new boolean[] {false, true}) {
      LockedTransferWallets locked =
          locked(sender(status, available, "0.00"), recipient("ACTIVE", "0.00"));
      Runnable call =
          hold
              ? () -> service.hold(locked, m(amount))
              : () -> service.complete(locked, m(amount), REFERENCE);
      if (expected == null) {
        call.run();
      } else {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
      }
    }
    if (expected != null) {
      verifyNothingWritten();
    }
  }

  @Test
  void aClosedSenderUnderTheLocksIsAServerError() {
    LockedTransferWallets locked =
        locked(sender("CLOSED", "1000.00", "0.00"), recipient("ACTIVE", "0.00"));

    assertThatThrownBy(() -> service.complete(locked, m("10.00"), REFERENCE))
        .isInstanceOf(IllegalStateException.class);
    verifyNothingWritten();
  }

  @Test
  void aVersionThatMovedUnderTheLockFailsBeforeTheLedger() {
    when(wallets.updateBalances(eq(RECIPIENT), any(), any(), anyLong())).thenReturn(0);
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "0.00"), recipient("ACTIVE", "0.00"));

    assertThatThrownBy(() -> service.complete(locked, m("10.00"), REFERENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("version");
    verifyNoInteractions(ledger);
  }

  @Test
  void aUsageVersionThatMovedUnderTheLockFailsBeforeTheLedger() {
    when(usages.updateUsedAndReserved(any(), anyString(), any(), any(), any(), anyLong()))
        .thenReturn(0);
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "0.00"), recipient("ACTIVE", "0.00"));

    assertThatThrownBy(() -> service.complete(locked, m("10.00"), REFERENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("version");
    verifyNoInteractions(ledger);
  }

  @Test
  void lockedWalletsServeOneOperationOnly() {
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "0.00"), recipient("ACTIVE", "0.00"));
    service.complete(locked, m("10.00"), REFERENCE);

    assertThatThrownBy(() -> service.complete(locked, m("10.00"), "TRF-2"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.hold(locked, m("10.00")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.release(locked, m("10.00"), DAY))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.capture(locked, m("10.00"), DAY, "TRF-3"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void callerErrorsAreProgrammingErrors() {
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "0.00"), recipient("ACTIVE", "0.00"));

    assertThatThrownBy(() -> service.complete(locked, Money.zero(Money.THB), REFERENCE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.complete(locked, m("-1.00"), REFERENCE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.complete(locked, m("1.00"), " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.complete(locked, m("1.00"), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.complete(null, m("1.00"), REFERENCE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.hold(locked, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.release(locked, m("1.00"), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.precheck(null, m("1.00")))
        .isInstanceOf(NullPointerException.class);
    // None of them used the locked wallets: they still serve one operation.
    service.complete(locked, m("1.00"), REFERENCE);
  }

  // Hold -----------------------------------------------------------------------------------------

  @Test
  void aHoldReservesOnTheSenderAndTheDayWithoutLedgerOrRecipient() {
    usage(DAY, "10.00", "5.00");
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "5.00"), recipient("ACTIVE", "7.00"));

    LocalDate limitDate = service.hold(locked, m("300.00"));

    assertThat(limitDate).isEqualTo(DAY);
    InOrder order = inOrder(wallets, usages);
    order.verify(usages).lockOrCreate(SENDER, TRANSFER, DAY);
    order.verify(wallets).updateBalances(SENDER, d("700.00"), d("305.00"), 7);
    order.verify(usages).updateUsedAndReserved(SENDER, TRANSFER, DAY, d("10.00"), d("305.00"), 3);
    verify(wallets, never()).updateBalances(eq(RECIPIENT), any(), any(), anyLong());
    verifyNoInteractions(ledger);
  }

  @Test
  void aHoldAfterBangkokMidnightCountsOnTheNewDay() {
    service = at(AFTER_MIDNIGHT);
    usage(NEXT_DAY, "0.00", "0.00");
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "0.00"), recipient("ACTIVE", "0.00"));

    assertThat(service.hold(locked, m("1.00"))).isEqualTo(NEXT_DAY);
    verify(usages).lockOrCreate(SENDER, TRANSFER, NEXT_DAY);
  }

  // Release --------------------------------------------------------------------------------------

  @Test
  void aReleaseUsesTheStoredDayAndWorksOnAFrozenOrClosedSender() {
    // The clock is on the next Bangkok day; the hold was reserved on DAY.
    service = at(AFTER_MIDNIGHT);
    usage(DAY, "10.00", "300.00");
    for (String status : new String[] {"ACTIVE", "FROZEN", "CLOSED"}) {
      LockedTransferWallets locked =
          locked(sender(status, "700.00", "300.00"), recipient("CLOSED", "7.00"));

      service.release(locked, m("300.00"), DAY);
    }

    verify(usages, org.mockito.Mockito.times(3)).lockExisting(SENDER, TRANSFER, DAY);
    verify(wallets, org.mockito.Mockito.times(3))
        .updateBalances(SENDER, d("1000.00"), d("0.00"), 7);
    verify(usages, org.mockito.Mockito.times(3))
        .updateUsedAndReserved(SENDER, TRANSFER, DAY, d("10.00"), d("0.00"), 3);
    verify(usages, never()).lockOrCreate(any(), anyString(), any());
    verify(wallets, never()).updateBalances(eq(RECIPIENT), any(), any(), anyLong());
    verifyNoInteractions(ledger);
  }

  @Test
  void aReleaseOfMoreThanIsReservedIsAProgrammingError() {
    usage(DAY, "0.00", "300.00");
    LockedTransferWallets onWallet =
        locked(sender("ACTIVE", "700.00", "299.99"), recipient("ACTIVE", "0.00"));
    assertThatThrownBy(() -> service.release(onWallet, m("300.00"), DAY))
        .isInstanceOf(IllegalStateException.class);

    usage(DAY, "0.00", "299.99");
    LockedTransferWallets onUsage =
        locked(sender("ACTIVE", "700.00", "300.00"), recipient("ACTIVE", "0.00"));
    assertThatThrownBy(() -> service.release(onUsage, m("300.00"), DAY))
        .isInstanceOf(IllegalStateException.class);

    when(usages.lockExisting(SENDER, TRANSFER, NEXT_DAY)).thenReturn(Optional.empty());
    LockedTransferWallets noRow =
        locked(sender("ACTIVE", "700.00", "300.00"), recipient("ACTIVE", "0.00"));
    assertThatThrownBy(() -> service.release(noRow, m("300.00"), NEXT_DAY))
        .isInstanceOf(IllegalStateException.class);
    verifyNothingWritten();
  }

  // Capture --------------------------------------------------------------------------------------

  @Test
  void aCaptureSettlesTheHoldOnTheStoredDayAndPostsTheLedger() {
    service = at(AFTER_MIDNIGHT);
    usage(DAY, "10.00", "305.00");
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "700.00", "305.00"), recipient("ACTIVE", "7.00"));

    TransferSettlement settlement = service.capture(locked, m("300.00"), DAY, REFERENCE);

    assertThat(settlement.limitDate()).isEqualTo(DAY);
    assertThat(settlement.ledgerTransactionId()).isEqualTo(LEDGER_TX);
    InOrder order = inOrder(wallets, usages, ledger);
    order.verify(usages).lockExisting(SENDER, TRANSFER, DAY);
    order.verify(wallets).updateBalances(SENDER, d("700.00"), d("5.00"), 7);
    order.verify(wallets).updateBalances(RECIPIENT, d("307.00"), d("0.00"), 7);
    order.verify(usages).updateUsedAndReserved(SENDER, TRANSFER, DAY, d("310.00"), d("5.00"), 3);
    order.verify(ledger).postTransfer(SENDER, RECIPIENT, REFERENCE, m("300.00"));
    verify(usages, never()).lockOrCreate(any(), anyString(), any());
  }

  @Test
  void aCaptureNeedsTwoActiveWalletsAndTheReservedAmount() {
    usage(DAY, "0.00", "300.00");
    for (String[] statuses :
        new String[][] {{"FROZEN", "ACTIVE"}, {"ACTIVE", "FROZEN"}, {"CLOSED", "ACTIVE"}}) {
      LockedTransferWallets locked =
          locked(sender(statuses[0], "0.00", "300.00"), recipient(statuses[1], "0.00"));
      assertThatThrownBy(() -> service.capture(locked, m("300.00"), DAY, REFERENCE))
          .isInstanceOf(IllegalStateException.class);
    }
    LockedTransferWallets short1 =
        locked(sender("ACTIVE", "0.00", "299.99"), recipient("ACTIVE", "0.00"));
    assertThatThrownBy(() -> service.capture(short1, m("300.00"), DAY, REFERENCE))
        .isInstanceOf(IllegalStateException.class);
    usage(DAY, "0.00", "100.00");
    LockedTransferWallets short2 =
        locked(sender("ACTIVE", "0.00", "300.00"), recipient("ACTIVE", "0.00"));
    assertThatThrownBy(() -> service.capture(short2, m("300.00"), DAY, REFERENCE))
        .isInstanceOf(IllegalStateException.class);
    verifyNothingWritten();
  }

  // Review fixes ---------------------------------------------------------------------------------

  /** Code review 1: the pre-check names the Bangkok day a BLOCKED row stores (MVP-ADR-22). */
  @Test
  void thePrecheckReturnsTheBangkokBusinessDayOfTheClock() {
    when(wallets.findByCustomer(SENDER_CUSTOMER))
        .thenReturn(Optional.of(sender("ACTIVE", "500.00", "0.00")));
    when(usages.find(SENDER, TRANSFER, NEXT_DAY)).thenReturn(Optional.empty());

    assertThat(at(BEFORE_MIDNIGHT).precheck(SENDER_CUSTOMER, m("1.00")).limitDate()).isEqualTo(DAY);
    assertThat(at(AFTER_MIDNIGHT).precheck(SENDER_CUSTOMER, m("1.00")).limitDate())
        .isEqualTo(NEXT_DAY);
    // The same day hold() reserves on with the same clock.
    WalletTransferService afterMidnight = at(AFTER_MIDNIGHT);
    usage(NEXT_DAY, "0.00", "0.00");
    when(wallets.lockBothForUpdate(SENDER, RECIPIENT))
        .thenReturn(List.of(sender("ACTIVE", "500.00", "0.00"), recipient("ACTIVE", "0.00")));
    assertThat(afterMidnight.hold(afterMidnight.lock(SENDER, RECIPIENT), m("1.00")))
        .isEqualTo(NEXT_DAY);
  }

  /** Security L1: D14 is enforced, not trusted: no money lock without the timeouts. */
  @Test
  void theLockRefusesATransactionWithoutTheTimeouts() {
    when(timeouts.applied()).thenReturn(false);

    assertThatThrownBy(() -> service.lock(SENDER, RECIPIENT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("D14");
    verify(wallets, never()).lockBothForUpdate(any(), any());
  }

  /** Security L2: the handle serves only the transaction that locked the rows. */
  @Test
  void lockedWalletsAreRefusedInAnotherTransaction() {
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "0.00"), recipient("ACTIVE", "0.00"));
    // The locking transaction completes; a new one begins on the thread.
    List<TransactionSynchronization> ending =
        TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationManager.clearSynchronization();
    ending.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> service.complete(locked, m("10.00"), REFERENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another transaction");
    verifyNothingWritten();
  }

  @Test
  void lockedWalletsAreRefusedInsideANestedTransactionBeforeTheirsCompletes() {
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "1000.00", "0.00"), recipient("ACTIVE", "0.00"));
    // What REQUIRES_NEW does: the outer synchronizations are suspended, a new list starts.
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> service.hold(locked, m("10.00")))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.clearSynchronization();
    assertThatThrownBy(() -> service.hold(locked, m("10.00")))
        .isInstanceOf(IllegalStateException.class);
    verifyNothingWritten();
  }

  /** Code review 3: an approval checks both wallets, the sender first, and releases if needed. */
  @ParameterizedTest(name = "sender {0}, recipient {1} -> {2}")
  @CsvSource({
    "FROZEN, ACTIVE, SENDER_FROZEN",
    "CLOSED, ACTIVE, SENDER_CLOSED",
    "ACTIVE, FROZEN, RECIPIENT_FROZEN",
    "ACTIVE, CLOSED, RECIPIENT_CLOSED",
    "FROZEN, CLOSED, SENDER_FROZEN",
    "CLOSED, FROZEN, SENDER_CLOSED"
  })
  void anApprovalWithAnUnavailableWalletReleasesTheHold(
      String senderStatus, String recipientStatus, UnavailableWallet expected) {
    service = at(AFTER_MIDNIGHT);
    usage(DAY, "10.00", "300.00");
    LockedTransferWallets locked =
        locked(sender(senderStatus, "700.00", "300.00"), recipient(recipientStatus, "7.00"));

    ApprovalOutcome outcome = service.approve(locked, m("300.00"), DAY, REFERENCE);

    assertThat(outcome).isEqualTo(new ApprovalOutcome.ReleasedWalletUnavailable(expected));
    verify(wallets).updateBalances(SENDER, d("1000.00"), d("0.00"), 7);
    verify(usages).updateUsedAndReserved(SENDER, TRANSFER, DAY, d("10.00"), d("0.00"), 3);
    verify(wallets, never()).updateBalances(eq(RECIPIENT), any(), any(), anyLong());
    verifyNoInteractions(ledger);
  }

  @Test
  void anApprovalWithTwoActiveWalletsCaptures() {
    service = at(AFTER_MIDNIGHT);
    usage(DAY, "10.00", "300.00");
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "700.00", "300.00"), recipient("ACTIVE", "7.00"));

    ApprovalOutcome outcome = service.approve(locked, m("300.00"), DAY, REFERENCE);

    assertThat(outcome)
        .isEqualTo(new ApprovalOutcome.Captured(new TransferSettlement(DAY, LEDGER_TX)));
    verify(wallets).updateBalances(SENDER, d("700.00"), d("0.00"), 7);
    verify(wallets).updateBalances(RECIPIENT, d("307.00"), d("0.00"), 7);
    verify(usages).updateUsedAndReserved(SENDER, TRANSFER, DAY, d("310.00"), d("0.00"), 3);
    verify(ledger).postTransfer(SENDER, RECIPIENT, REFERENCE, m("300.00"));
    assertThatThrownBy(() -> service.approve(locked, m("300.00"), DAY, "TRF-2"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void approvalArgumentsAreChecked() {
    LockedTransferWallets locked =
        locked(sender("ACTIVE", "700.00", "300.00"), recipient("ACTIVE", "7.00"));
    assertThatThrownBy(() -> service.approve(null, m("1.00"), DAY, REFERENCE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.approve(locked, m("1.00"), null, REFERENCE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.approve(locked, m("1.00"), DAY, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalOutcome.Captured(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ApprovalOutcome.ReleasedWalletUnavailable(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> UnavailableWallet.of("UNKNOWN", "ACTIVE"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(UnavailableWallet.of("ACTIVE", "ACTIVE")).isNull();
    assertThatThrownBy(() -> new TransferPrecheck(null, DAY))
        .isInstanceOf(NullPointerException.class);
  }
}
