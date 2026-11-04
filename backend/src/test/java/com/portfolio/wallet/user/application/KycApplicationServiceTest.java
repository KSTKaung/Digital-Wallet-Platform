package com.portfolio.wallet.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import com.portfolio.wallet.user.persistence.KycApplicationRepository;
import com.portfolio.wallet.user.persistence.KycApplicationRow;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Error codes, scoping and audit of the customer's KYC actions (documentations/02 §2.2). */
class KycApplicationServiceTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID APPLICATION = UUID.randomUUID();
  private static final String REFERENCE = "AB1234567";
  private static final KycApplicationCommand COMMAND =
      new KycApplicationCommand("FULL", "PASSPORT", REFERENCE, "Somchai", "Jaidee");

  private final CustomerRepository customers = mock(CustomerRepository.class);
  private final KycApplicationRepository applications = mock(KycApplicationRepository.class);
  private final CustomerWallets wallets = mock(CustomerWallets.class);
  private final AuditService audit = mock(AuditService.class);
  private final KycApplicationService service =
      new KycApplicationService(customers, applications, wallets, audit);
  private InetAddress ip;

  @BeforeEach
  void setUp() throws Exception {
    ip = InetAddress.getByName("192.0.2.1");
  }

  private static KycApplicationRow row(String status) {
    Instant now = Instant.parse("2026-11-03T00:00:00Z");
    return new KycApplicationRow(
        APPLICATION,
        CUSTOMER,
        "FULL",
        "PASSPORT",
        REFERENCE,
        "Somchai",
        "Jaidee",
        "DRAFT".equals(status) ? null : LocalDate.of(1990, 5, 17),
        "DRAFT".equals(status) ? null : "TH",
        status,
        "DRAFT".equals(status) ? null : now,
        null,
        null,
        "REJECTED".equals(status) ? "Unreadable" : null,
        now,
        now);
  }

  private AuditEntry recordedEntry() {
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(captor.capture());
    return captor.getValue();
  }

  // Create -------------------------------------------------------------------------------------

  @Test
  void createInsertsTheCustomerThenADraftAndAuditsWithoutKycContent() {
    when(applications.insertDraftIfNoneActive(
            CUSTOMER, "FULL", "PASSPORT", REFERENCE, "Somchai", "Jaidee"))
        .thenReturn(Optional.of(APPLICATION));
    when(applications.findOwned(APPLICATION, CUSTOMER)).thenReturn(Optional.of(row("DRAFT")));

    KycApplicationView view = service.create(CUSTOMER, COMMAND, ip);

    verify(customers).insertIfAbsent(CUSTOMER);
    assertThat(view.status()).isEqualTo("DRAFT");
    assertThat(view.maskedDocumentReference()).isEqualTo("***4567");
    assertThat(view.toString()).doesNotContain(REFERENCE, "Somchai", "Jaidee");
    AuditEntry entry = recordedEntry();
    assertThat(entry.action()).isEqualTo(UserAudit.KYC_CREATE);
    assertThat(entry.resource()).isEqualTo(UserAudit.KYC_APPLICATION);
    assertThat(entry.resourceId()).isEqualTo(APPLICATION.toString());
    assertThat(entry.actorId()).isEqualTo(CUSTOMER);
    assertThat(entry.actorType()).isEqualTo(ActorType.CUSTOMER);
    assertThat(entry.result()).isEqualTo(AuditResult.SUCCESS);
    assertThat(entry.clientIp()).isEqualTo(ip);
    assertThat(entry.oldValue()).isNull();
    assertThat(entry.newValue()).isEqualTo(Map.of("status", "DRAFT"));
  }

  @Test
  void createWithAnActiveApplicationIsKycAlreadyActiveAndNotAudited() {
    when(applications.insertDraftIfNoneActive(any(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(CUSTOMER, COMMAND, ip))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.KYC_ALREADY_ACTIVE);
    verifyNoInteractions(audit);
  }

  static KycApplicationCommand withField(String field, String value) {
    return new KycApplicationCommand(
        field.equals("verificationLevel") ? value : "BASIC",
        field.equals("documentType") ? value : "ID_CARD",
        field.equals("documentReference") ? value : REFERENCE,
        field.equals("firstName") ? value : "Somchai",
        field.equals("lastName") ? value : "Jaidee");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"verificationLevel", "documentType", "documentReference", "firstName", "lastName"})
  void invalidDetailsAreAValidationErrorNamingTheFieldOnly(String field) {
    for (String value : new String[] {null, "bad\u0000value"}) {
      assertThatThrownBy(() -> service.create(CUSTOMER, withField(field, value), ip))
          .isInstanceOf(ApiException.class)
          .hasMessageStartingWith(field + ": ")
          .hasMessageNotContaining("bad")
          .extracting(e -> ((ApiException) e).errorCode())
          .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
    verifyNoInteractions(customers, applications, audit);
  }

  // Edit and submit ----------------------------------------------------------------------------

  @Test
  void editReplacesADraftUnderItsLockAndAuditsTheState() {
    when(applications.findOwnedForUpdate(APPLICATION, CUSTOMER))
        .thenReturn(Optional.of(row("DRAFT")));
    when(applications.findOwned(APPLICATION, CUSTOMER)).thenReturn(Optional.of(row("DRAFT")));
    KycApplicationCommand changed =
        new KycApplicationCommand("BASIC", "ID_CARD", "ZZ0001111", "Somsri", "Jaidee");

    service.edit(CUSTOMER, APPLICATION, changed, ip);

    verify(applications)
        .updateDraft(APPLICATION, CUSTOMER, "BASIC", "ID_CARD", "ZZ0001111", "Somsri", "Jaidee");
    AuditEntry entry = recordedEntry();
    assertThat(entry.action()).isEqualTo(UserAudit.KYC_UPDATE);
    assertThat(entry.oldValue()).isEqualTo(Map.of("status", "DRAFT"));
    assertThat(entry.newValue()).isEqualTo(Map.of("status", "DRAFT"));
  }

  private void profile(LocalDate dateOfBirth, String countryCode) {
    when(customers.findForUpdate(CUSTOMER))
        .thenReturn(
            Optional.of(
                new CustomerRow(CUSTOMER, null, null, dateOfBirth, countryCode, null, null)));
  }

  @Test
  void submitNeedsTheDateOfBirthAndTheCountryInTheProfile() {
    when(applications.findOwnedForUpdate(APPLICATION, CUSTOMER))
        .thenReturn(Optional.of(row("DRAFT")));

    profile(null, "TH");
    assertThatThrownBy(() -> service.submit(CUSTOMER, APPLICATION, ip))
        .isInstanceOf(ApiException.class)
        .hasMessage(KycApplicationService.MISSING_DATE_OF_BIRTH)
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.VALIDATION_ERROR);

    profile(LocalDate.of(1990, 1, 1), null);
    assertThatThrownBy(() -> service.submit(CUSTOMER, APPLICATION, ip))
        .isInstanceOf(ApiException.class)
        .hasMessage(KycApplicationService.MISSING_COUNTRY_CODE);

    verify(applications, never()).submit(any(), any());
    verifyNoInteractions(audit);
  }

  @Test
  void submitMovesADraftAndAuditsTheTransition() {
    profile(LocalDate.of(1990, 1, 1), "TH");
    when(applications.findOwnedForUpdate(APPLICATION, CUSTOMER))
        .thenReturn(Optional.of(row("DRAFT")));
    when(applications.findOwned(APPLICATION, CUSTOMER))
        .thenReturn(Optional.of(row("PENDING_REVIEW")));

    KycApplicationView view = service.submit(CUSTOMER, APPLICATION, ip);

    // Owner follow-up #2: application row, then profile row (the approval's order), then submit.
    InOrder order = inOrder(applications, customers);
    order.verify(applications).findOwnedForUpdate(APPLICATION, CUSTOMER);
    order.verify(customers).findForUpdate(CUSTOMER);
    order.verify(applications).submit(APPLICATION, CUSTOMER);
    verify(customers, never()).find(any());
    assertThat(view.status()).isEqualTo("PENDING_REVIEW");
    AuditEntry entry = recordedEntry();
    assertThat(entry.action()).isEqualTo(UserAudit.KYC_SUBMIT);
    assertThat(entry.oldValue()).isEqualTo(Map.of("status", "DRAFT"));
    assertThat(entry.newValue()).isEqualTo(Map.of("status", "PENDING_REVIEW"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING_REVIEW", "APPROVED", "REJECTED"})
  void editOrSubmitOutsideDraftIsInvalidKycState(String status) {
    when(applications.findOwnedForUpdate(APPLICATION, CUSTOMER))
        .thenReturn(Optional.of(row(status)));

    assertThatThrownBy(() -> service.edit(CUSTOMER, APPLICATION, COMMAND, ip))
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_KYC_STATE);
    assertThatThrownBy(() -> service.submit(CUSTOMER, APPLICATION, ip))
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_KYC_STATE);
    verify(applications, never()).submit(any(), any());
    verifyNoInteractions(audit);
  }

  /** The repository is scoped by the caller, so "not mine" and "missing" are both empty. */
  @Test
  void anotherCustomersOrAMissingApplicationIsTheGenericNotFound() {
    when(applications.findOwnedForUpdate(any(), any())).thenReturn(Optional.empty());
    when(applications.findOwned(any(), any())).thenReturn(Optional.empty());

    for (Runnable call :
        new Runnable[] {
          () -> service.edit(CUSTOMER, APPLICATION, COMMAND, ip),
          () -> service.submit(CUSTOMER, APPLICATION, ip),
          () -> service.get(CUSTOMER, APPLICATION)
        }) {
      assertThatThrownBy(call::run)
          .isInstanceOf(ApiException.class)
          .hasMessage(ApiException.notFound().getMessage())
          .extracting(e -> ((ApiException) e).errorCode())
          .isEqualTo(ErrorCode.NOT_FOUND);
    }
    verifyNoInteractions(audit);
  }

  @Test
  void getShowsTheRejectionReasonAndTheMaskedReference() {
    when(applications.findOwned(APPLICATION, CUSTOMER)).thenReturn(Optional.of(row("REJECTED")));

    KycApplicationView view = service.get(CUSTOMER, APPLICATION);

    assertThat(view.rejectionReason()).isEqualTo("Unreadable");
    assertThat(view.maskedDocumentReference()).isEqualTo("***4567");
    assertThat(view.firstName()).isEqualTo("Somchai");
  }

  // Status -------------------------------------------------------------------------------------

  @Test
  void statusWithoutAnApplicationIsNone() {
    when(applications.findCurrent(CUSTOMER)).thenReturn(Optional.empty());

    assertThat(service.status(CUSTOMER)).isEqualTo(new KycStatusView("NONE", null, false));
  }

  @Test
  void statusReportsTheCurrentApplicationAndTheWalletSeam() {
    when(applications.findCurrent(CUSTOMER)).thenReturn(Optional.of(row("PENDING_REVIEW")));
    when(wallets.exists(CUSTOMER)).thenReturn(true);

    assertThat(service.status(CUSTOMER))
        .isEqualTo(new KycStatusView("PENDING_REVIEW", "FULL", true));
  }

  @Test
  void commandsAndRowsDoNotPrintKycContent() {
    assertThat(COMMAND.toString()).doesNotContain(REFERENCE, "Somchai");
    assertThat(row("DRAFT").toString()).doesNotContain(REFERENCE, "Somchai", "Jaidee");
  }
}
