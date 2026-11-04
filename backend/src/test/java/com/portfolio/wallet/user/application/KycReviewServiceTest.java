package com.portfolio.wallet.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.user.domain.VerificationLevel;
import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import com.portfolio.wallet.user.persistence.KycApplicationRepository;
import com.portfolio.wallet.user.persistence.KycApplicationRow;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Staff KYC decisions (documentations/02 §2.7; doc 06 MVP-ADR-10 amendment; doc 08 §4B). */
class KycReviewServiceTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID REVIEWER = UUID.randomUUID();
  private static final UUID APPLICATION = UUID.randomUUID();
  private static final UUID WALLET = UUID.randomUUID();
  private static final String REFERENCE = "AB1234567";
  private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
  // The date of birth and country copied onto the application at submit (K4).
  private static final LocalDate SNAPSHOT_DATE_OF_BIRTH = LocalDate.of(1990, 5, 17);
  private static final String SNAPSHOT_COUNTRY = "TH";

  private final CustomerRepository customers = mock(CustomerRepository.class);
  private final KycApplicationRepository applications = mock(KycApplicationRepository.class);
  private final CustomerWallets wallets = mock(CustomerWallets.class);
  private final NotificationOutbox outbox = mock(NotificationOutbox.class);
  private final AuditService audit = mock(AuditService.class);
  private final KycReviewService service =
      new KycReviewService(customers, applications, wallets, outbox, audit);
  private InetAddress ip;

  @BeforeEach
  void setUp() throws Exception {
    ip = InetAddress.getByName("192.0.2.1");
    when(customers.find(CUSTOMER)).thenReturn(Optional.of(profile(null)));
  }

  private static KycApplicationRow row(String status) {
    return row(status, CUSTOMER);
  }

  private static KycApplicationRow row(String status, UUID customer) {
    boolean decided = "APPROVED".equals(status) || "REJECTED".equals(status);
    return new KycApplicationRow(
        APPLICATION,
        customer,
        "FULL",
        "PASSPORT",
        REFERENCE,
        "Somchai",
        "Jaidee",
        SNAPSHOT_DATE_OF_BIRTH,
        SNAPSHOT_COUNTRY,
        status,
        NOW,
        decided ? NOW : null,
        decided ? REVIEWER : null,
        "REJECTED".equals(status) ? "Unreadable" : null,
        NOW,
        NOW);
  }

  private static CustomerRow profile(String firstName) {
    return new CustomerRow(
        CUSTOMER,
        firstName,
        firstName == null ? null : "Jaidee",
        // The current profile differs from the snapshot: a review must never read it (K4).
        LocalDate.of(1985, 1, 1),
        "GB",
        NOW,
        NOW);
  }

  private void pending() {
    when(applications.findSubmittedForUpdate(APPLICATION))
        .thenReturn(Optional.of(row("PENDING_REVIEW")));
  }

  private void afterDecision(String status) {
    when(applications.findSubmitted(APPLICATION)).thenReturn(Optional.of(row(status)));
  }

  private AuditEntry recordedEntry() {
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(captor.capture());
    return captor.getValue();
  }

  private static void assertError(Throwable thrown, ErrorCode code) {
    assertThat(thrown).isInstanceOf(ApiException.class);
    assertThat(((ApiException) thrown).errorCode()).isEqualTo(code);
  }

  // List and detail ----------------------------------------------------------------------------

  @Test
  void listMasksTheReferenceAndTheNameAndPages() {
    when(applications.findSubmittedPage("PENDING_REVIEW", 20, 40))
        .thenReturn(List.of(row("PENDING_REVIEW")));
    when(applications.countSubmitted("PENDING_REVIEW")).thenReturn(41L);

    KycReviewPage page = service.list("PENDING_REVIEW", 2, 20);

    assertThat(page.page()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(20);
    assertThat(page.totalElements()).isEqualTo(41);
    assertThat(page.totalPages()).isEqualTo(3);
    KycReviewSummary item = page.items().getFirst();
    assertThat(item.maskedDocumentReference()).isEqualTo("***4567");
    assertThat(item.maskedName()).isEqualTo("Somchai J.");
    assertThat(item.customerId()).isEqualTo(CUSTOMER);
    assertThat(item.toString()).doesNotContain(REFERENCE, "Somchai", "4567");
  }

  @Test
  void anEmptyListHasNoPages() {
    when(applications.findSubmittedPage("REJECTED", 10, 0)).thenReturn(List.of());

    KycReviewPage page = service.list("REJECTED", 0, 10);

    assertThat(page.items()).isEmpty();
    assertThat(page.totalPages()).isZero();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"DRAFT", "pending_review", "ANY", ""})
  void listRefusesDraftsAndUnknownStatuses(String status) {
    Throwable thrown = catchThrowable(() -> service.list(status, 0, 20));

    assertError(thrown, ErrorCode.VALIDATION_ERROR);
    assertThat(thrown).hasMessage(KycReviewService.INVALID_STATUS);
    verify(applications, never()).findSubmittedPage(anyString(), anyInt(), anyLong());
  }

  @Test
  void detailShowsTheFullNameTheProfileAndAMaskedReference() {
    afterDecision("PENDING_REVIEW");

    KycReviewDetail detail = service.get(APPLICATION);

    assertThat(detail.firstName()).isEqualTo("Somchai");
    assertThat(detail.lastName()).isEqualTo("Jaidee");
    assertThat(detail.maskedDocumentReference()).isEqualTo("***4567");
    assertThat(detail.dateOfBirth()).isEqualTo(SNAPSHOT_DATE_OF_BIRTH);
    assertThat(detail.countryCode()).isEqualTo(SNAPSHOT_COUNTRY);
    assertThat(detail.toString()).doesNotContain(REFERENCE, "Somchai", "1990");
  }

  /** K4: the reviewer sees the snapshot taken at submit, never the current profile. */
  @Test
  void theDetailReadsTheApplicationsSnapshotAndNeverTheProfile() {
    afterDecision("PENDING_REVIEW");

    KycReviewDetail detail = service.get(APPLICATION);

    assertThat(detail.dateOfBirth()).isEqualTo(SNAPSHOT_DATE_OF_BIRTH);
    assertThat(detail.countryCode()).isEqualTo(SNAPSHOT_COUNTRY);
    verifyNoInteractions(customers);
  }

  @Test
  void anUnknownOrDraftApplicationIsTheGeneric404() {
    when(applications.findSubmitted(APPLICATION)).thenReturn(Optional.empty());

    Throwable thrown = catchThrowable(() -> service.get(APPLICATION));

    assertError(thrown, ErrorCode.NOT_FOUND);
    assertThat(thrown).hasMessage(ApiException.notFound().getMessage());
  }

  // Approve ------------------------------------------------------------------------------------

  /**
   * J4: the application is approved before the name is copied, so V8 can check that the name comes
   * from the application approved in the same transaction.
   */
  @Test
  void approveLocksInOrderDecidesCopiesTheNameAuditsOpensTheWalletAndQueuesTheEmail() {
    pending();
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.of(profile(null)));
    when(customers.setLegalName(CUSTOMER, "Somchai", "Jaidee")).thenReturn(1);
    when(applications.approve(APPLICATION, REVIEWER)).thenReturn(1);
    when(wallets.open(CUSTOMER, VerificationLevel.FULL, REVIEWER, ip)).thenReturn(WALLET);
    afterDecision("APPROVED");

    KycReviewDetail detail = service.approve(REVIEWER, APPLICATION, ip);

    assertThat(detail.status()).isEqualTo("APPROVED");
    InOrder order = inOrder(applications, customers, audit, wallets, outbox);
    order.verify(applications).findSubmittedForUpdate(APPLICATION);
    order.verify(customers).findForUpdate(CUSTOMER);
    order.verify(applications).approve(APPLICATION, REVIEWER);
    order.verify(customers).setLegalName(CUSTOMER, "Somchai", "Jaidee");
    order.verify(audit).record(any());
    order.verify(wallets).open(CUSTOMER, VerificationLevel.FULL, REVIEWER, ip);
    order.verify(outbox).enqueue(CUSTOMER, EmailKind.KYC_APPROVED);
    // The current profile's date of birth and country are never read (K4).
    verify(customers, never()).find(any());
  }

  @Test
  void approvalIsAuditedAsTheReviewerWithStatesOnly() {
    pending();
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.of(profile(null)));
    when(customers.setLegalName(any(), any(), any())).thenReturn(1);
    when(applications.approve(APPLICATION, REVIEWER)).thenReturn(1);
    afterDecision("APPROVED");

    service.approve(REVIEWER, APPLICATION, ip);

    AuditEntry entry = recordedEntry();
    assertThat(entry.actorType()).isEqualTo(ActorType.STAFF);
    assertThat(entry.actorId()).isEqualTo(REVIEWER);
    assertThat(entry.action()).isEqualTo("KYC_APPLICATION_APPROVE");
    assertThat(entry.resource()).isEqualTo("KYC_APPLICATION");
    assertThat(entry.resourceId()).isEqualTo(APPLICATION.toString());
    assertThat(entry.oldValue()).isEqualTo(Map.of("status", "PENDING_REVIEW"));
    assertThat(entry.newValue())
        .isEqualTo(Map.of("status", "APPROVED", "customerId", CUSTOMER.toString()));
    assertThat(entry.toString()).doesNotContain(REFERENCE, "Somchai", "Jaidee");
  }

  /** Only the customer id and the kind go to the outbox: no KYC content, no address. */
  @Test
  void theApprovalQueuesOnlyTheCustomerAndTheKind() {
    pending();
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.of(profile(null)));
    when(customers.setLegalName(any(), any(), any())).thenReturn(1);
    when(applications.approve(APPLICATION, REVIEWER)).thenReturn(1);
    afterDecision("APPROVED");

    service.approve(REVIEWER, APPLICATION, ip);

    verify(outbox).enqueue(CUSTOMER, EmailKind.KYC_APPROVED);
    verifyNoMoreInteractions(outbox);
  }

  @ParameterizedTest
  @ValueSource(strings = {"APPROVED", "REJECTED"})
  void aDecidedApplicationCannotBeApproved(String status) {
    when(applications.findSubmittedForUpdate(APPLICATION)).thenReturn(Optional.of(row(status)));

    Throwable thrown = catchThrowable(() -> service.approve(REVIEWER, APPLICATION, ip));

    assertError(thrown, ErrorCode.INVALID_KYC_STATE);
    verifyNoInteractions(wallets, outbox, audit);
    verify(customers, never()).findForUpdate(any());
  }

  @Test
  void approvingAnUnknownApplicationIsTheGeneric404() {
    when(applications.findSubmittedForUpdate(APPLICATION)).thenReturn(Optional.empty());

    Throwable thrown = catchThrowable(() -> service.approve(REVIEWER, APPLICATION, ip));

    assertError(thrown, ErrorCode.NOT_FOUND);
    verifyNoInteractions(wallets, outbox, audit, customers);
  }

  @Test
  void aReviewerCannotDecideTheirOwnApplication() {
    when(applications.findSubmittedForUpdate(APPLICATION))
        .thenReturn(Optional.of(row("PENDING_REVIEW", REVIEWER)));

    assertError(
        catchThrowable(() -> service.approve(REVIEWER, APPLICATION, ip)), ErrorCode.FORBIDDEN);
    assertError(
        catchThrowable(() -> service.reject(REVIEWER, APPLICATION, "Unreadable", ip)),
        ErrorCode.FORBIDDEN);
    verifyNoInteractions(wallets, outbox, audit);
  }

  @Test
  void aProfileWithALegalNameAlreadyRefusesTheApproval() {
    pending();
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.of(profile("Somchai")));

    Throwable thrown = catchThrowable(() -> service.approve(REVIEWER, APPLICATION, ip));

    assertError(thrown, ErrorCode.INVALID_KYC_STATE);
    verify(customers, never()).setLegalName(any(), any(), any());
    verifyNoInteractions(wallets, outbox, audit);
  }

  @Test
  void aLegalNameWrittenConcurrentlyRefusesTheApproval() {
    pending();
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.of(profile(null)));
    when(customers.setLegalName(any(), any(), any())).thenReturn(0);

    when(applications.approve(APPLICATION, REVIEWER)).thenReturn(1);

    assertError(
        catchThrowable(() -> service.approve(REVIEWER, APPLICATION, ip)),
        ErrorCode.INVALID_KYC_STATE);
    verifyNoInteractions(wallets, outbox, audit);
  }

  @Test
  void theStatusGuardOfTheUpdateRefusesTheApproval() {
    pending();
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.of(profile(null)));
    when(customers.setLegalName(any(), any(), any())).thenReturn(1);
    when(applications.approve(APPLICATION, REVIEWER)).thenReturn(0);

    assertError(
        catchThrowable(() -> service.approve(REVIEWER, APPLICATION, ip)),
        ErrorCode.INVALID_KYC_STATE);
    verify(customers, never()).setLegalName(any(), any(), any());
    verifyNoInteractions(wallets, outbox, audit);
  }

  @Test
  void aMissingProfileIsAnInternalError() {
    pending();
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.approve(REVIEWER, APPLICATION, ip))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aWalletFailurePropagatesSoTheApprovalRollsBack() {
    pending();
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.of(profile(null)));
    when(customers.setLegalName(any(), any(), any())).thenReturn(1);
    when(applications.approve(APPLICATION, REVIEWER)).thenReturn(1);
    when(wallets.open(any(), any(), any(), any()))
        .thenThrow(new ApiException(ErrorCode.INVALID_KYC_STATE, "already"));

    assertError(
        catchThrowable(() -> service.approve(REVIEWER, APPLICATION, ip)),
        ErrorCode.INVALID_KYC_STATE);
    verifyNoInteractions(outbox);
  }

  // Reject -------------------------------------------------------------------------------------

  @Test
  void rejectStoresTheNormalisedReasonAuditsWithoutItAndEmailsWithoutIt() {
    pending();
    when(applications.reject(APPLICATION, REVIEWER, "Document photo is unreadable")).thenReturn(1);
    afterDecision("REJECTED");

    KycReviewDetail detail =
        service.reject(REVIEWER, APPLICATION, "  Document photo is unreadable ", ip);

    assertThat(detail.status()).isEqualTo("REJECTED");
    verify(applications).reject(APPLICATION, REVIEWER, "Document photo is unreadable");
    AuditEntry entry = recordedEntry();
    assertThat(entry.action()).isEqualTo("KYC_APPLICATION_REJECT");
    assertThat(entry.actorType()).isEqualTo(ActorType.STAFF);
    assertThat(entry.newValue())
        .isEqualTo(Map.of("status", "REJECTED", "customerId", CUSTOMER.toString()));
    assertThat(entry.toString()).doesNotContain("unreadable");
    verify(outbox).enqueue(CUSTOMER, EmailKind.KYC_REJECTED);
    verifyNoMoreInteractions(outbox);
    verifyNoInteractions(wallets);
    verify(customers, never()).findForUpdate(any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Reference AB1234567 is not valid",
        "reference ab-123 4567 is blurred",
        "a.b.1.2.3.4.5.6.7",
        "ＡＢ１２３４５６７ is not valid"
      })
  void aReasonContainingTheReferenceIsAValidationErrorThatEchoesNothing(String reason) {
    pending();

    Throwable thrown = catchThrowable(() -> service.reject(REVIEWER, APPLICATION, reason, ip));

    assertError(thrown, ErrorCode.VALIDATION_ERROR);
    assertThat(thrown).hasMessage("reason: must not contain the document reference");
    verify(applications, never()).reject(any(), any(), any());
    verifyNoInteractions(audit, outbox);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        " ",
        "bad\u0000reason",
        "zero\u200Bwidth",
        "bidi\u202Emark",
        "non\u200Cjoiner",
        "...",
        "?! -"
      })
  void aBlankControlFormatOrLetterlessReasonIsAValidationError(String reason) {
    pending();

    Throwable thrown = catchThrowable(() -> service.reject(REVIEWER, APPLICATION, reason, ip));

    assertError(thrown, ErrorCode.VALIDATION_ERROR);
    assertThat(thrown.getMessage()).startsWith("reason: ");
    if (reason != null && !reason.isBlank()) {
      assertThat(thrown.getMessage()).doesNotContain(reason);
    }
    verify(applications, never()).reject(any(), any(), any());
    verifyNoInteractions(audit, outbox);
  }

  @ParameterizedTest
  @ValueSource(strings = {"APPROVED", "REJECTED"})
  void aDecidedApplicationCannotBeRejected(String status) {
    when(applications.findSubmittedForUpdate(APPLICATION)).thenReturn(Optional.of(row(status)));

    assertError(
        catchThrowable(() -> service.reject(REVIEWER, APPLICATION, "Unreadable", ip)),
        ErrorCode.INVALID_KYC_STATE);
    verifyNoInteractions(audit, outbox);
  }

  @Test
  void theStatusGuardOfTheUpdateRefusesTheRejection() {
    pending();
    when(applications.reject(any(), any(), any())).thenReturn(0);

    assertError(
        catchThrowable(() -> service.reject(REVIEWER, APPLICATION, "Unreadable", ip)),
        ErrorCode.INVALID_KYC_STATE);
    verifyNoInteractions(audit, outbox);
  }

  @Test
  void rejectingAnUnknownApplicationIsTheGeneric404() {
    when(applications.findSubmittedForUpdate(APPLICATION)).thenReturn(Optional.empty());

    assertError(
        catchThrowable(() -> service.reject(REVIEWER, APPLICATION, "Unreadable", ip)),
        ErrorCode.NOT_FOUND);
  }
}
