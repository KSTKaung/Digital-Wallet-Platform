package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.ledger.application.LedgerAccountService;
import com.portfolio.wallet.wallet.domain.WalletNumber;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/** Wallet creation on KYC approval (documentations/06 MVP-ADR-10, -20; doc 07 §3). */
class WalletServiceTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID REVIEWER = UUID.randomUUID();
  private static final UUID LEDGER_ACCOUNT = UUID.randomUUID();
  private static final int ATTEMPTS = 3;

  private final WalletRepository repository = mock(WalletRepository.class);
  private final LedgerAccountService ledger = mock(LedgerAccountService.class);
  private final WalletNumberGenerator numbers = mock(WalletNumberGenerator.class);
  private final AuditService audit = mock(AuditService.class);
  private final WalletService service =
      new WalletService(repository, ledger, numbers, audit, new WalletProperties(ATTEMPTS));
  private InetAddress ip;

  @BeforeEach
  void setUp() throws Exception {
    ip = InetAddress.getByName("192.0.2.1");
    when(numbers.next()).thenReturn(WalletNumber.parse("12345678903"));
    when(ledger.openCustomerAccount(any())).thenReturn(LEDGER_ACCOUNT);
  }

  private void insertSucceeds() {
    when(repository.insertIfNumberFree(
            any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, UUID.class)));
  }

  @ParameterizedTest
  @CsvSource({"BASIC, BASIC", "FULL, VERIFIED"})
  void opensAnActiveZeroBalanceThbWalletWithTheTierOfTheLevel(String level, String tier) {
    insertSucceeds();

    OpenedWallet opened = service.openForApprovedKyc(CUSTOMER, level, REVIEWER, ip);

    verify(repository)
        .insertIfNumberFree(
            opened.walletId(),
            "12345678903",
            CUSTOMER,
            tier,
            "ACTIVE",
            "THB",
            new BigDecimal("0.00"),
            new BigDecimal("0.00"));
    assertThat(opened.ledgerAccountId()).isEqualTo(LEDGER_ACCOUNT);
  }

  @Test
  void theLedgerAccountBelongsToTheNewWallet() {
    insertSucceeds();

    OpenedWallet opened = service.openForApprovedKyc(CUSTOMER, "BASIC", REVIEWER, ip);

    verify(ledger).openCustomerAccount(opened.walletId());
  }

  @Test
  void theCreationIsAuditedByTheReviewerWithoutTheWalletNumber() {
    insertSucceeds();

    OpenedWallet opened = service.openForApprovedKyc(CUSTOMER, "FULL", REVIEWER, ip);

    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(captor.capture());
    AuditEntry entry = captor.getValue();
    assertThat(entry.actorType()).isEqualTo(ActorType.STAFF);
    assertThat(entry.actorId()).isEqualTo(REVIEWER);
    assertThat(entry.action()).isEqualTo("WALLET_CREATE");
    assertThat(entry.resource()).isEqualTo("WALLET");
    assertThat(entry.resourceId()).isEqualTo(opened.walletId().toString());
    assertThat(entry.result()).isEqualTo(AuditResult.SUCCESS);
    assertThat(entry.clientIp()).isEqualTo(ip);
    assertThat(entry.newValue())
        .isEqualTo(
            Map.of(
                "customerId", CUSTOMER.toString(),
                "status", "ACTIVE",
                "tier", "VERIFIED",
                "currency", "THB",
                "ledgerAccountId", LEDGER_ACCOUNT.toString()));
    assertThat(entry.toString()).doesNotContain("12345678903");
  }

  @Test
  void aTakenNumberIsReplacedByAFreshOne() {
    when(numbers.next())
        .thenReturn(WalletNumber.parse("12345678903"), WalletNumber.parse("00000000000"));
    when(repository.insertIfNumberFree(
            any(), eq("12345678903"), any(), anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.insertIfNumberFree(
            any(), eq("00000000000"), any(), anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, UUID.class)));

    OpenedWallet opened = service.openForApprovedKyc(CUSTOMER, "BASIC", REVIEWER, ip);

    verify(repository, times(2))
        .insertIfNumberFree(
            any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any());
    verify(ledger).openCustomerAccount(opened.walletId());
    verify(audit, times(1)).record(any());
  }

  @Test
  void givesUpAfterTheConfiguredAttemptsAndCreatesNothing() {
    when(repository.insertIfNumberFree(
            any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.openForApprovedKyc(CUSTOMER, "BASIC", REVIEWER, ip))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(String.valueOf(ATTEMPTS));
    verify(numbers, times(ATTEMPTS)).next();
    verifyNoInteractions(ledger, audit);
  }

  @Test
  void aSecondWalletForTheCustomerIsRefused() {
    when(repository.insertIfNumberFree(
            any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
        .thenThrow(new DuplicateKeyException("uq_wallets_customer"));

    assertThatThrownBy(() -> service.openForApprovedKyc(CUSTOMER, "BASIC", REVIEWER, ip))
        .isInstanceOf(CustomerAlreadyHasWalletException.class);
    verify(numbers, times(1)).next();
    verifyNoInteractions(ledger, audit);
  }

  @Test
  void anUnknownLevelIsAProgrammingError() {
    assertThatThrownBy(() -> service.openForApprovedKyc(CUSTOMER, "PREMIUM", REVIEWER, ip))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never())
        .insertIfNumberFree(
            any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void existsAsksTheRepository() {
    when(repository.existsForCustomer(CUSTOMER)).thenReturn(true);

    assertThat(service.existsForCustomer(CUSTOMER)).isTrue();
    assertThat(service.existsForCustomer(UUID.randomUUID())).isFalse();
  }

  @Test
  void generatedNumbersAreValidAndVary() {
    WalletNumberGenerator generator = new WalletNumberGenerator();

    WalletNumber first = generator.next();
    WalletNumber second = generator.next();

    assertThat(WalletNumber.parse(first.value())).isEqualTo(first);
    assertThat(first.value()).hasSize(WalletNumber.LENGTH);
    assertThat(second).isNotEqualTo(first);
  }
}
