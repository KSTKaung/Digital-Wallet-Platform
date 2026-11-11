package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.error.RateLimitedException;
import com.portfolio.wallet.wallet.persistence.LookupAttemptRepository;
import com.portfolio.wallet.wallet.persistence.LookupAttemptRepository.WindowCount;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Rules of {@link WalletLookupService} (documentations/06 MVP-ADR-19, -20). */
class WalletLookupServiceTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID TARGET_CUSTOMER = UUID.randomUUID();
  private static final UUID TARGET_WALLET = UUID.randomUUID();
  private static final String CALLER_NUMBER = new WalletNumberGenerator().next().value();
  private static final String TARGET_NUMBER = new WalletNumberGenerator().next().value();
  private static final String IP = "192.0.2.10";

  private final WalletRepository wallets = mock(WalletRepository.class);
  private final LookupAttemptRepository attempts = mock(LookupAttemptRepository.class);
  private final RecipientNames names = mock(RecipientNames.class);
  private final AuditService audit = mock(AuditService.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final TransactionTimeouts timeouts = mock(TransactionTimeouts.class);

  private final WalletLookupService service =
      new WalletLookupService(
          wallets,
          attempts,
          names,
          audit,
          new LookupProperties(
              10,
              30,
              Duration.ofMinutes(1),
              new LookupProperties.Purge(true, Duration.ofHours(1), 100)),
          transactions,
          timeouts);

  private static WalletRow wallet(UUID id, UUID customer, String number, String status) {
    return new WalletRow(
        id,
        number,
        customer,
        "BASIC",
        status,
        "THB",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    when(wallets.findByCustomer(CALLER))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), CALLER, CALLER_NUMBER, "ACTIVE")));
    when(wallets.findByWalletNumber(TARGET_NUMBER))
        .thenReturn(Optional.of(wallet(TARGET_WALLET, TARGET_CUSTOMER, TARGET_NUMBER, "ACTIVE")));
    when(names.maskedLegalName(TARGET_CUSTOMER)).thenReturn(Optional.of("Somchai J."));
    counts(0, 0);
  }

  private void counts(int user, int ip) {
    when(attempts.countForUser(any(), anyLong())).thenReturn(new WindowCount(user, 17));
    when(attempts.countForIp(anyString(), anyLong())).thenReturn(new WindowCount(ip, 42));
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  @Test
  void aMatchIsCountedAuditedAndMasked() {
    WalletLookupView view = service.lookup(CALLER, TARGET_NUMBER, IP);

    assertThat(view.maskedName()).isEqualTo("Somchai J.");
    assertThat(view.maskedWalletNumber()).isEqualTo("*******" + TARGET_NUMBER.substring(7));
    InOrder order = inOrder(attempts, wallets, audit);
    order.verify(attempts).lockKeys(CALLER, IP);
    order.verify(attempts).countForUser(CALLER, 60);
    order.verify(attempts).countForIp(IP, 60);
    order.verify(attempts).insert(CALLER, IP);
    order.verify(wallets).findByWalletNumber(TARGET_NUMBER);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    order.verify(audit).record(entry.capture());
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.SUCCESS);
    assertThat(entry.getValue().resourceId()).isEqualTo(TARGET_WALLET.toString());
    assertThat(entry.getValue().newValue()).doesNotContainValue(TARGET_NUMBER);
  }

  /** Doc 07 D14 open risk Z1: the timeouts are set before the advisory locks are taken. */
  @Test
  void theTimeoutsAreSetBeforeTheAdvisoryLocks() {
    service.lookup(CALLER, TARGET_NUMBER, IP);

    InOrder order = inOrder(timeouts, attempts);
    order.verify(timeouts).apply();
    order.verify(attempts).lockKeys(CALLER, IP);
  }

  @Test
  void ownFrozenClosedMissingAndNamelessWalletsAreTheSameNotFound() {
    when(wallets.findByWalletNumber(CALLER_NUMBER))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), CALLER, CALLER_NUMBER, "ACTIVE")));
    String frozen = new WalletNumberGenerator().next().value();
    when(wallets.findByWalletNumber(frozen))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), UUID.randomUUID(), frozen, "FROZEN")));
    String closed = new WalletNumberGenerator().next().value();
    when(wallets.findByWalletNumber(closed))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), UUID.randomUUID(), closed, "CLOSED")));
    String nameless = new WalletNumberGenerator().next().value();
    when(wallets.findByWalletNumber(nameless))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), UUID.randomUUID(), nameless, "ACTIVE")));

    for (String number : new String[] {CALLER_NUMBER, frozen, closed, nameless, "00000000000"}) {
      assertThatThrownBy(() -> service.lookup(CALLER, number, IP))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.getMessage()).isEqualTo(ApiException.notFound().getMessage()))
          .extracting(e -> ((ApiException) e).errorCode())
          .isEqualTo(ErrorCode.NOT_FOUND);
    }
    verify(attempts, org.mockito.Mockito.times(5)).insert(CALLER, IP);
    verify(audit, org.mockito.Mockito.times(5)).record(any());
  }

  @Test
  void overEitherLimitIs429WithTheLongerWaitAndNothingIsRecorded() {
    counts(10, 0);
    assertThatThrownBy(() -> service.lookup(CALLER, TARGET_NUMBER, IP))
        .isInstanceOfSatisfying(
            RateLimitedException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(17));
    counts(3, 30);
    assertThatThrownBy(() -> service.lookup(CALLER, TARGET_NUMBER, IP))
        .isInstanceOfSatisfying(
            RateLimitedException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(42));
    counts(10, 30);
    assertThatThrownBy(() -> service.lookup(CALLER, TARGET_NUMBER, IP))
        .isInstanceOfSatisfying(
            RateLimitedException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(42));

    verify(attempts, never()).insert(any(), any());
    verifyNoInteractions(audit);
  }

  @Test
  void theRetryAfterIsAtLeastOneSecond() {
    assertThat(new RateLimitedException(0).retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  void theCallerNeedsAnActiveWallet() {
    when(wallets.findByCustomer(CALLER)).thenReturn(Optional.empty());
    assertCode(() -> service.lookup(CALLER, TARGET_NUMBER, IP), ErrorCode.KYC_REQUIRED);

    when(wallets.findByCustomer(CALLER))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), CALLER, CALLER_NUMBER, "FROZEN")));
    assertCode(() -> service.lookup(CALLER, TARGET_NUMBER, IP), ErrorCode.WALLET_FROZEN);

    when(wallets.findByCustomer(CALLER))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), CALLER, CALLER_NUMBER, "CLOSED")));
    assertThatThrownBy(() -> service.lookup(CALLER, TARGET_NUMBER, IP))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(attempts, audit);
  }

  @Test
  void onlyElevenDigitsAreAccepted() {
    for (String bad : new String[] {null, "", "1234567890", "123456789012", "1234567890a", " 1"}) {
      assertCode(() -> service.lookup(CALLER, bad, IP), ErrorCode.VALIDATION_ERROR);
    }
    verifyNoInteractions(attempts, wallets);
  }

  // The two validation steps (owner, 2026-11-04, X2) ---------------------------------------------

  private static String withWrongCheckDigit(String number) {
    char last = number.charAt(10);
    return number.substring(0, 10) + (last == '9' ? '0' : (char) (last + 1));
  }

  @Test
  void theFormatIsCheckedBeforeTheCallerAndIsNeitherCountedNorAudited() {
    when(wallets.findByCustomer(CALLER)).thenReturn(Optional.empty());

    assertCode(() -> service.lookup(CALLER, "1234567890", IP), ErrorCode.VALIDATION_ERROR);
    verifyNoInteractions(wallets, attempts, audit, transactions);
  }

  @Test
  void aWrongCheckDigitIsCountedAuditedAndNotFoundWithoutAWalletQuery() {
    String wrong = withWrongCheckDigit(TARGET_NUMBER);

    assertThatThrownBy(() -> service.lookup(CALLER, wrong, IP))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
              assertThat(e.getMessage()).isEqualTo(ApiException.notFound().getMessage());
            });

    InOrder order = inOrder(attempts, audit);
    order.verify(attempts).lockKeys(CALLER, IP);
    order.verify(attempts).countForUser(CALLER, 60);
    order.verify(attempts).countForIp(IP, 60);
    order.verify(attempts).insert(CALLER, IP);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    order.verify(audit).record(entry.capture());
    assertThat(entry.getValue().result()).isEqualTo(AuditResult.FAILURE);
    assertThat(entry.getValue().newValue()).containsEntry("outcome", "NOT_FOUND");
    verify(wallets, never()).findByWalletNumber(any());
    verifyNoInteractions(names);
  }

  @Test
  void theRateLimitComesBeforeTheCheckDigit() {
    counts(10, 0);

    assertThatThrownBy(() -> service.lookup(CALLER, withWrongCheckDigit(TARGET_NUMBER), IP))
        .isInstanceOf(RateLimitedException.class);
    verify(attempts, never()).insert(any(), any());
    verifyNoInteractions(audit);
  }

  @Test
  void aRemoteAddressThatIsNotALiteralIsAServerError() {
    assertThatThrownBy(() -> service.lookup(CALLER, TARGET_NUMBER, "example.com"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ipv6CountsPerSlash64AndIpv4AsItIs() throws Exception {
    assertThat(WalletLookupService.ipKey(InetAddress.getByName("2001:db8:1:2:3:4:5:6")))
        .isEqualTo(WalletLookupService.ipKey(InetAddress.getByName("2001:db8:1:2:ffff::1")))
        .isEqualTo("2001:db8:1:2:0:0:0:0");
    assertThat(WalletLookupService.ipKey(InetAddress.getByName("192.0.2.7")))
        .isEqualTo("192.0.2.7");

    service.lookup(CALLER, TARGET_NUMBER, "2001:db8:1:2:3:4:5:6");
    verify(attempts).lockKeys(CALLER, "2001:db8:1:2:0:0:0:0");
  }

  @Test
  void theMaskKeepsTheLengthAndTheLastFourDigits() {
    assertThat(WalletLookupService.masked("12345678901")).isEqualTo("*******8901");
  }

  @Test
  void propertiesAreChecked() {
    LookupProperties.Purge purge = new LookupProperties.Purge(true, Duration.ofHours(1), 1);
    assertThatThrownBy(() -> new LookupProperties(0, 30, Duration.ofMinutes(1), purge))
        .hasMessageContaining("per-user");
    assertThatThrownBy(() -> new LookupProperties(10, 0, Duration.ofMinutes(1), purge))
        .hasMessageContaining("per-ip");
    assertThatThrownBy(() -> new LookupProperties(10, 30, Duration.ofMillis(1500), purge))
        .hasMessageContaining("window");
    assertThatThrownBy(() -> new LookupProperties(10, 30, null, purge))
        .hasMessageContaining("window");
    assertThatThrownBy(() -> new LookupProperties(10, 30, Duration.ofMinutes(1), null))
        .hasMessageContaining("purge");
    assertThatThrownBy(() -> new LookupProperties.Purge(true, Duration.ZERO, 1))
        .hasMessageContaining("interval");
    assertThatThrownBy(() -> new LookupProperties.Purge(true, null, 1))
        .hasMessageContaining("interval");
    assertThatThrownBy(() -> new LookupProperties.Purge(true, Duration.ofHours(1), 0))
        .hasMessageContaining("batch-size");
  }

  @Test
  void thePurgeDeletesBatchesOlderThanTheWindowAndSurvivesAFailure() {
    LookupPurgeService purge =
        new LookupPurgeService(
            attempts,
            new LookupProperties(
                10,
                30,
                Duration.ofMinutes(1),
                new LookupProperties.Purge(true, Duration.ofHours(1), 2)));
    when(attempts.deleteOlderThan(60, 2)).thenReturn(2, 2, 1);
    assertThat(purge.purgeExpired()).isEqualTo(5);

    when(attempts.deleteOlderThan(60, 2))
        .thenReturn(2)
        .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
    assertThat(purge.purgeExpired()).isEqualTo(2);
  }

  // The recipient step of POST /transfers (doc 08 §4C.2 Q3 step 3, Q5) ------------------------

  @Test
  void aFoundTransferRecipientIsLimitedButNeitherCountedNorAudited() {
    TransferRecipient recipient = service.resolveTransferRecipient(CALLER, TARGET_NUMBER, IP);

    assertThat(recipient.walletId()).isEqualTo(TARGET_WALLET);
    assertThat(recipient.customerId()).isEqualTo(TARGET_CUSTOMER);
    assertThat(recipient.maskedName()).isEqualTo("Somchai J.");
    assertThat(recipient.maskedWalletNumber()).isEqualTo("*******" + TARGET_NUMBER.substring(7));
    assertThat(recipient.toString()).doesNotContain(TARGET_WALLET.toString());
    // The same locks and window check as a lookup, first.
    InOrder order = inOrder(timeouts, attempts, wallets);
    order.verify(timeouts).apply();
    order.verify(attempts).lockKeys(CALLER, IP);
    order.verify(attempts).countForUser(CALLER, 60);
    order.verify(attempts).countForIp(IP, 60);
    order.verify(wallets).findByWalletNumber(TARGET_NUMBER);
    verify(attempts, never()).insert(any(), any());
    verifyNoInteractions(audit);
  }

  @Test
  void aTransferToTheSendersOwnWalletIsSelfTransferAfterTheNotFoundCasesAndNotCounted() {
    when(wallets.findByWalletNumber(CALLER_NUMBER))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), CALLER, CALLER_NUMBER, "ACTIVE")));

    assertCode(
        () -> service.resolveTransferRecipient(CALLER, CALLER_NUMBER, IP), ErrorCode.SELF_TRANSFER);
    verify(attempts, never()).insert(any(), any());
    verifyNoInteractions(audit);

    // The sender's own wallet, frozen, is one of the 404 cases (checked first), and counts.
    when(wallets.findByWalletNumber(CALLER_NUMBER))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), CALLER, CALLER_NUMBER, "FROZEN")));
    assertCode(
        () -> service.resolveTransferRecipient(CALLER, CALLER_NUMBER, IP), ErrorCode.NOT_FOUND);
    verify(attempts).insert(CALLER, IP);
  }

  @Test
  void anIneligibleTransferRecipientIsCountedAuditedAndTheIdenticalNotFound() {
    String frozen = new WalletNumberGenerator().next().value();
    when(wallets.findByWalletNumber(frozen))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), UUID.randomUUID(), frozen, "FROZEN")));
    String nameless = new WalletNumberGenerator().next().value();
    when(wallets.findByWalletNumber(nameless))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), UUID.randomUUID(), nameless, "ACTIVE")));
    String wrongDigit =
        TARGET_NUMBER.substring(0, 10) + (char) ('0' + (TARGET_NUMBER.charAt(10) - '0' + 1) % 10);

    for (String number : new String[] {frozen, nameless, wrongDigit, "00000000000"}) {
      assertThatThrownBy(() -> service.resolveTransferRecipient(CALLER, number, IP))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.getMessage()).isEqualTo(ApiException.notFound().getMessage()));
    }
    verify(attempts, org.mockito.Mockito.times(4)).insert(CALLER, IP);
    ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit, org.mockito.Mockito.times(4)).record(entries.capture());
    assertThat(entries.getAllValues())
        .allSatisfy(
            entry -> {
              assertThat(entry.action()).isEqualTo("WALLET_LOOKUP");
              assertThat(entry.result()).isEqualTo(AuditResult.FAILURE);
              assertThat(entry.newValue()).containsEntry("outcome", "NOT_FOUND");
            });
    verify(wallets, never()).findByWalletNumber(wrongDigit);
    // The attempt is recorded after the window counts (Y1: its stamp follows the lock wait).
    InOrder order = inOrder(attempts);
    order.verify(attempts).countForIp(IP, 60);
    order.verify(attempts).insert(CALLER, IP);
  }

  @Test
  void aFrozenSenderIsResolvedButNeedsAWalletAndAValidFormat() {
    when(wallets.findByCustomer(CALLER))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), CALLER, CALLER_NUMBER, "FROZEN")));
    assertThat(service.resolveTransferRecipient(CALLER, TARGET_NUMBER, IP).walletId())
        .isEqualTo(TARGET_WALLET);

    org.mockito.Mockito.clearInvocations(attempts, audit);
    assertThatThrownBy(() -> service.resolveTransferRecipient(CALLER, "123", IP))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.getMessage()).isEqualTo("receiverWalletNumber: must be 11 digits");
            });
    when(wallets.findByCustomer(CALLER)).thenReturn(Optional.empty());
    assertCode(
        () -> service.resolveTransferRecipient(CALLER, TARGET_NUMBER, IP), ErrorCode.KYC_REQUIRED);
    verifyNoInteractions(attempts, audit);
  }

  /** The limit applies to every resolution, found or not, so a 429 discloses nothing. */
  @Test
  void overEitherWindowEveryTransferResolutionIs429AndNothingIsRecorded() {
    when(wallets.findByWalletNumber(CALLER_NUMBER))
        .thenReturn(Optional.of(wallet(UUID.randomUUID(), CALLER, CALLER_NUMBER, "ACTIVE")));
    for (int[] counts : new int[][] {{10, 0}, {0, 30}}) {
      counts(counts[0], counts[1]);
      for (String number : new String[] {TARGET_NUMBER, CALLER_NUMBER, "00000000000"}) {
        assertThatThrownBy(() -> service.resolveTransferRecipient(CALLER, number, IP))
            .isInstanceOf(RateLimitedException.class);
      }
    }
    verify(attempts, never()).insert(any(), any());
    verify(wallets, never()).findByWalletNumber(any());
    verifyNoInteractions(audit);
  }
}
