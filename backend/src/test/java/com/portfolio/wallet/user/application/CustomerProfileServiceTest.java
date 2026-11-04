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

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.application.ContactDetails;
import com.portfolio.wallet.auth.application.UserContacts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.time.BusinessTimeProperties;
import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import com.portfolio.wallet.user.persistence.KycApplicationRepository;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** The caller's own profile (documentations/02 §2.2; doc 08 §4B.2 Q15; owner, 2026-11-03). */
class CustomerProfileServiceTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final BusinessTimeProperties PROPERTIES =
      new BusinessTimeProperties(ZoneId.of("Asia/Bangkok"));

  /** 00:30 on 4 November in Bangkok, still 3 November in UTC. */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-03T17:30:00Z"), ZoneOffset.UTC);

  private static final LocalDate BANGKOK_TODAY = LocalDate.of(2026, 11, 4);
  private static final LocalDate DOB = LocalDate.of(1990, 5, 17);

  private final CustomerRepository customers = mock(CustomerRepository.class);
  private final KycApplicationRepository applications = mock(KycApplicationRepository.class);
  private final UserContacts contacts = mock(UserContacts.class);
  private final AuditService audit = mock(AuditService.class);
  private final CustomerProfileService service =
      new CustomerProfileService(customers, applications, contacts, audit, PROPERTIES, CLOCK);

  private void stored(String first, String last, LocalDate dob, String country) {
    CustomerRow row = new CustomerRow(CUSTOMER, first, last, dob, country, null, null);
    when(customers.find(CUSTOMER)).thenReturn(Optional.of(row));
    when(customers.findForUpdate(CUSTOMER)).thenReturn(Optional.of(row));
    when(contacts.find(CUSTOMER))
        .thenReturn(Optional.of(new ContactDetails("alice@example.com", "+66812345678")));
  }

  @Test
  void theProfileCreatesTheCustomerRowAndMasksContactDetails() {
    stored("Somchai", "Jaidee", DOB, "TH");

    CustomerProfile profile = service.profile(CUSTOMER);

    verify(customers).insertIfAbsent(CUSTOMER);
    assertThat(profile.maskedEmail()).isEqualTo("a***@example.com");
    assertThat(profile.maskedPhoneNumber()).isEqualTo("***5678");
    assertThat(profile.firstName()).isEqualTo("Somchai");
    assertThat(profile.lastName()).isEqualTo("Jaidee");
    assertThat(profile.dateOfBirth()).isEqualTo(DOB);
    assertThat(profile.countryCode()).isEqualTo("TH");
    assertThat(profile.toString()).doesNotContain("alice", "Somchai", "1990");
  }

  @Test
  void aNewCustomerHasNoLegalNameYet() {
    stored(null, null, null, null);

    CustomerProfile profile = service.profile(CUSTOMER);

    assertThat(profile.firstName()).isNull();
    assertThat(profile.dateOfBirth()).isNull();
  }

  @Test
  void aMissingAccountIsTheGenericNotFound() {
    when(customers.find(CUSTOMER))
        .thenReturn(Optional.of(new CustomerRow(CUSTOMER, null, null, null, null, null, null)));
    when(contacts.find(CUSTOMER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.profile(CUSTOMER))
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  void anUpdateLocksTheRowWritesBothFieldsAndAuditsWithoutValues() throws Exception {
    stored(null, null, DOB, "TH");
    InetAddress ip = InetAddress.getByName("192.0.2.1");

    service.updateProfile(CUSTOMER, DOB, "TH", ip);

    InOrder order = inOrder(customers, applications, audit);
    order.verify(customers).insertIfAbsent(CUSTOMER);
    order.verify(customers).findForUpdate(CUSTOMER);
    order.verify(applications).locksProfile(CUSTOMER);
    order.verify(customers).updateProfile(CUSTOMER, DOB, "TH");
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    order.verify(audit).record(captor.capture());
    AuditEntry entry = captor.getValue();
    assertThat(entry.action()).isEqualTo(UserAudit.PROFILE_UPDATE);
    assertThat(entry.resource()).isEqualTo(UserAudit.CUSTOMER);
    assertThat(entry.resourceId()).isEqualTo(CUSTOMER.toString());
    assertThat(entry.clientIp()).isEqualTo(ip);
    assertThat(entry.oldValue()).isNull();
    assertThat(entry.newValue()).isNull();
  }

  @Test
  void todayInBangkokIsAValidDateOfBirthAndTomorrowIsNot() {
    stored(null, null, null, null);

    service.updateProfile(CUSTOMER, BANGKOK_TODAY, "TH", null);
    verify(customers).updateProfile(CUSTOMER, BANGKOK_TODAY, "TH");

    assertThatThrownBy(() -> service.updateProfile(CUSTOMER, BANGKOK_TODAY.plusDays(1), "TH", null))
        .isInstanceOf(ApiException.class)
        .hasMessage("dateOfBirth: must not be in the future")
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  void anUnknownCountryIsAValidationErrorAndNothingIsWritten() {
    assertThatThrownBy(() -> service.updateProfile(CUSTOMER, DOB, "XX", null))
        .isInstanceOf(ApiException.class)
        .hasMessage("countryCode: must be an ISO 3166-1 alpha-2 code");
    verifyNoInteractions(customers, applications, audit);
  }

  @Test
  void anApprovedApplicationLocksTheProfile() {
    stored(null, null, DOB, "TH");
    when(applications.locksProfile(CUSTOMER)).thenReturn(true);

    assertThatThrownBy(() -> service.updateProfile(CUSTOMER, DOB, "GB", null))
        .isInstanceOf(ApiException.class)
        .hasMessage(CustomerProfileService.LOCKED)
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_KYC_STATE);
    verify(customers, never()).updateProfile(any(), any(), any());
    verifyNoInteractions(audit);
  }

  /** The approval copies the legal name into the locked row, so a set name means approved. */
  @Test
  void aCopiedLegalNameLocksTheProfile() {
    stored("Somchai", "Jaidee", DOB, "TH");

    assertThatThrownBy(() -> service.updateProfile(CUSTOMER, DOB, "GB", null))
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_KYC_STATE);
    verify(customers, never()).updateProfile(any(), any(), any());
  }
}
