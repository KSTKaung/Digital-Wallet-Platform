package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.ledger.application.LedgerAccountService;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.wallet.domain.InvalidWalletStatusTransitionException;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Freeze and unfreeze (documentations/02 §2.3, §2.7; doc 06 MVP-ADR-10 amendment; Q9). */
class WalletStatusServiceTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID ADMIN = UUID.randomUUID();
  private static final UUID WALLET = UUID.randomUUID();
  private static final long VERSION = 7;

  private final WalletRepository repository = mock(WalletRepository.class);
  private final LedgerAccountService ledger = mock(LedgerAccountService.class);
  private final AuditService audit = mock(AuditService.class);
  private final NotificationOutbox outbox = mock(NotificationOutbox.class);
  private final TransactionTimeouts timeouts = mock(TransactionTimeouts.class);
  private final WalletStatusService service =
      new WalletStatusService(repository, ledger, audit, outbox, timeouts);
  private InetAddress ip;

  @Test
  void theD14TimeoutsAreSetBeforeTheWalletIsLocked() {
    stored("ACTIVE");

    service.freezeOwn(CUSTOMER, ip);
    service.freezeByStaff(ADMIN, CUSTOMER, "Suspicious activity", ip);
    service.unfreezeByStaff(ADMIN, CUSTOMER, "Cleared", ip);

    InOrder order = inOrder(timeouts, repository);
    for (int i = 0; i < 3; i++) {
      order.verify(timeouts).apply();
      order.verify(repository).findByCustomerForUpdate(CUSTOMER);
    }
  }

  @Test
  void aTimeoutsFailureLocksNothing() {
    doThrow(new IllegalStateException("outside a transaction")).when(timeouts).apply();

    assertThatThrownBy(() -> service.freezeOwn(CUSTOMER, ip))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(repository, ledger, audit, outbox);
  }

  @BeforeEach
  void setUp() throws Exception {
    ip = InetAddress.getByName("192.0.2.1");
    when(repository.updateStatus(any(), anyString(), anyLong())).thenReturn(1);
  }

  private void stored(String status) {
    when(repository.findByCustomerForUpdate(CUSTOMER))
        .thenReturn(
            Optional.of(
                new WalletRow(
                    WALLET,
                    "12345678903",
                    CUSTOMER,
                    "BASIC",
                    status,
                    "THB",
                    new BigDecimal("10.00"),
                    new BigDecimal("0.00"),
                    VERSION,
                    Instant.EPOCH,
                    Instant.EPOCH)));
  }

  private AuditEntry audited() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    verify(audit, never()).recordIndependently(any());
    return entry.getValue();
  }

  @Test
  void aCustomerFreezeLocksTheWalletThenTheLedgerAccountAndAuditsWithoutAReason() {
    stored("ACTIVE");

    WalletStatusView view = service.freezeOwn(CUSTOMER, ip);

    assertThat(view.status()).isEqualTo("FROZEN");
    InOrder order = inOrder(repository, ledger, audit);
    order.verify(repository).findByCustomerForUpdate(CUSTOMER);
    order.verify(repository).updateStatus(WALLET, "FROZEN", VERSION);
    order.verify(ledger).freezeCustomerAccount(WALLET);
    order.verify(audit).record(any());
    AuditEntry entry = audited();
    assertThat(entry.action()).isEqualTo(WalletAudit.WALLET_FREEZE);
    assertThat(entry.resource()).isEqualTo(WalletAudit.WALLET);
    assertThat(entry.resourceId()).isEqualTo(WALLET.toString());
    assertThat(entry.actorType()).isEqualTo(ActorType.CUSTOMER);
    assertThat(entry.actorId()).isEqualTo(CUSTOMER);
    assertThat(entry.result()).isEqualTo(AuditResult.SUCCESS);
    assertThat(entry.clientIp()).isEqualTo(ip);
    assertThat(entry.oldValue()).isEqualTo(Map.of("status", "ACTIVE"));
    assertThat(entry.newValue())
        .containsEntry("status", "FROZEN")
        .containsEntry("ledgerAccountStatus", "FROZEN")
        .containsEntry("customerId", CUSTOMER.toString())
        .doesNotContainKey("reason");
    assertThat(entry.newValue().toString()).doesNotContain("12345678903");
  }

  @Test
  void aStaffFreezeRecordsTheStaffMemberAndTheReason() {
    stored("ACTIVE");

    assertThat(service.freezeByStaff(ADMIN, CUSTOMER, "Reported stolen phone", ip).status())
        .isEqualTo("FROZEN");

    verify(ledger).freezeCustomerAccount(WALLET);
    AuditEntry entry = audited();
    assertThat(entry.actorType()).isEqualTo(ActorType.STAFF);
    assertThat(entry.actorId()).isEqualTo(ADMIN);
    assertThat(entry.newValue()).containsEntry("reason", "Reported stolen phone");
  }

  @Test
  void aStaffUnfreezeMovesBothBackToActive() {
    stored("FROZEN");

    assertThat(service.unfreezeByStaff(ADMIN, CUSTOMER, "Customer verified", ip).status())
        .isEqualTo("ACTIVE");

    verify(repository).updateStatus(WALLET, "ACTIVE", VERSION);
    verify(ledger).unfreezeCustomerAccount(WALLET);
    verify(ledger, never()).freezeCustomerAccount(any());
    AuditEntry entry = audited();
    assertThat(entry.action()).isEqualTo(WalletAudit.WALLET_UNFREEZE);
    assertThat(entry.oldValue()).isEqualTo(Map.of("status", "FROZEN"));
    assertThat(entry.newValue())
        .containsEntry("status", "ACTIVE")
        .containsEntry("ledgerAccountStatus", "ACTIVE");
  }

  @Test
  void repeatingAFreezeOrAnUnfreezeChangesNothingAndAuditsNothing() {
    stored("FROZEN");
    assertThat(service.freezeOwn(CUSTOMER, ip).status()).isEqualTo("FROZEN");
    assertThat(service.freezeByStaff(ADMIN, CUSTOMER, "again", ip).status()).isEqualTo("FROZEN");

    stored("ACTIVE");
    assertThat(service.unfreezeByStaff(ADMIN, CUSTOMER, "again", ip).status()).isEqualTo("ACTIVE");

    verify(repository, never()).updateStatus(any(), anyString(), anyLong());
    verifyNoInteractions(ledger, audit, outbox);
  }

  @Test
  void aClosedWalletIsNeverReopenedOrFrozen() {
    stored("CLOSED");

    assertThatThrownBy(() -> service.unfreezeByStaff(ADMIN, CUSTOMER, "reopen", ip))
        .isInstanceOf(InvalidWalletStatusTransitionException.class);
    assertThatThrownBy(() -> service.freezeOwn(CUSTOMER, ip))
        .isInstanceOf(InvalidWalletStatusTransitionException.class);
    verify(repository, never()).updateStatus(any(), anyString(), anyLong());
    verifyNoInteractions(ledger, audit, outbox);
  }

  @Test
  void withoutAWalletTheCustomerGetsWalletNotFoundAndStaffTheGeneric404() {
    when(repository.findByCustomerForUpdate(CUSTOMER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.freezeOwn(CUSTOMER, ip))
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.WALLET_NOT_FOUND);
    assertThatThrownBy(() -> service.freezeByStaff(ADMIN, CUSTOMER, "reason", ip))
        .isInstanceOf(ApiException.class)
        .hasMessage(ApiException.notFound().getMessage())
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
    assertThatThrownBy(() -> service.unfreezeByStaff(ADMIN, CUSTOMER, "reason", ip))
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
    verifyNoInteractions(ledger, audit, outbox);
  }

  @Test
  void staffMustGiveAReason() {
    assertThatThrownBy(() -> service.freezeByStaff(ADMIN, CUSTOMER, " ", ip))
        .isInstanceOf(ApiException.class)
        .hasMessage("reason: must not be blank");
    assertThatThrownBy(() -> service.unfreezeByStaff(ADMIN, CUSTOMER, null, ip))
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
    verifyNoInteractions(repository, ledger, audit, outbox);
  }

  @Test
  void aVersionThatMovedUnderTheLockRollsBackBeforeTheLedger() {
    stored("ACTIVE");
    when(repository.updateStatus(any(), anyString(), anyLong())).thenReturn(0);

    assertThatThrownBy(() -> service.freezeOwn(CUSTOMER, ip))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("wallet version changed under its row lock");
    verifyNoInteractions(ledger, audit, outbox);
  }

  @Test
  void aLedgerFailurePropagatesSoTheTransactionRollsBack() {
    stored("ACTIVE");
    doThrow(new IllegalStateException("wallet without a ledger account"))
        .when(ledger)
        .freezeCustomerAccount(WALLET);

    assertThatThrownBy(() -> service.freezeOwn(CUSTOMER, ip))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(audit, outbox);
  }

  // Freeze emails through the outbox (MVP-ADR-10 amendment, MVP-ADR-25) -------------------------

  @Test
  void aStaffFreezeQueuesTheFrozenEmailAsTheLastStepOfTheTransaction() {
    stored("ACTIVE");

    service.freezeByStaff(ADMIN, CUSTOMER, "Reported stolen phone", ip);

    InOrder order = inOrder(repository, ledger, audit, outbox);
    order.verify(repository).updateStatus(WALLET, "FROZEN", VERSION);
    order.verify(ledger).freezeCustomerAccount(WALLET);
    order.verify(audit).record(any());
    order.verify(outbox).enqueue(CUSTOMER, EmailKind.WALLET_FROZEN);
  }

  @Test
  void aStaffUnfreezeQueuesTheUnfrozenEmail() {
    stored("FROZEN");

    service.unfreezeByStaff(ADMIN, CUSTOMER, "Customer verified", ip);

    verify(outbox).enqueue(CUSTOMER, EmailKind.WALLET_UNFROZEN);
  }

  /** Only the customer id and the kind go to the outbox: no reason, address or wallet details. */
  @Test
  void theOutboxGetsTheCustomerAndTheKindOnly() {
    stored("ACTIVE");
    service.freezeByStaff(ADMIN, CUSTOMER, "Distinctive reason 9182", ip);

    verify(outbox).enqueue(CUSTOMER, EmailKind.WALLET_FROZEN);
    verifyNoMoreInteractions(outbox);
  }

  @Test
  void theCustomersOwnFreezeSendsNothing() {
    stored("ACTIVE");

    service.freezeOwn(CUSTOMER, ip);

    verify(audit).record(any());
    verifyNoInteractions(outbox);
  }

  @Test
  void aRepeatedStaffChangeQueuesNothing() {
    stored("FROZEN");

    service.freezeByStaff(ADMIN, CUSTOMER, "again", ip);

    verifyNoInteractions(outbox);
  }

  /** A failure in the change throws before the email is queued, and the transaction rolls back. */
  @Test
  void aLedgerOrAuditFailureQueuesNoEmail() {
    stored("ACTIVE");
    doThrow(new IllegalStateException("forced ledger failure"))
        .when(ledger)
        .freezeCustomerAccount(WALLET);
    assertThatThrownBy(() -> service.freezeByStaff(ADMIN, CUSTOMER, "reason", ip))
        .isInstanceOf(IllegalStateException.class);

    stored("FROZEN");
    doThrow(new IllegalStateException("forced audit failure")).when(audit).record(any());
    assertThatThrownBy(() -> service.unfreezeByStaff(ADMIN, CUSTOMER, "reason", ip))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(outbox);
  }

  @Test
  void aVersionConflictOrAClosedWalletQueuesNoEmail() {
    stored("CLOSED");
    assertThatThrownBy(() -> service.freezeByStaff(ADMIN, CUSTOMER, "reason", ip))
        .isInstanceOf(InvalidWalletStatusTransitionException.class);

    stored("ACTIVE");
    when(repository.updateStatus(any(), anyString(), anyLong())).thenReturn(0);
    assertThatThrownBy(() -> service.freezeByStaff(ADMIN, CUSTOMER, "reason", ip))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(outbox);
  }

  // J6: the reason's content ---------------------------------------------------------------------

  @Test
  void aReasonWithFormatCharactersOrWithoutALetterOrDigitIsRefusedWithoutEchoingIt() {
    for (String reason :
        new String[] {"ok\u200Bnot", "rea\u202Eson", "...", "!!! ???", "\u00A0-"}) {
      assertThatThrownBy(() -> service.freezeByStaff(ADMIN, CUSTOMER, reason, ip))
          .isInstanceOf(ApiException.class)
          .satisfies(
              e -> {
                assertThat(((ApiException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                assertThat(e.getMessage()).startsWith("reason: ").doesNotContain(reason);
              });
    }
    verifyNoInteractions(repository, ledger, audit, outbox);
  }

  @Test
  void aReasonInThaiOrWithDigitsOnlyIsAccepted() {
    stored("ACTIVE");
    assertThat(service.freezeByStaff(ADMIN, CUSTOMER, "บัญชีถูกแจ้ง", ip).status())
        .isEqualTo("FROZEN");
    stored("FROZEN");
    assertThat(service.unfreezeByStaff(ADMIN, CUSTOMER, "12345", ip).status()).isEqualTo("ACTIVE");
  }
}
