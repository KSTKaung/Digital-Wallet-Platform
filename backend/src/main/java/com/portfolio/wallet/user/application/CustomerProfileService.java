package com.portfolio.wallet.user.application;

import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.application.ContactDetails;
import com.portfolio.wallet.auth.application.UserContacts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.time.BusinessTimeProperties;
import com.portfolio.wallet.user.domain.CustomerProfileRules;
import com.portfolio.wallet.user.domain.InvalidKycDetailsException;
import com.portfolio.wallet.user.domain.Masking;
import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import com.portfolio.wallet.user.persistence.KycApplicationRepository;
import java.net.InetAddress;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own profile (documentations/02 §2.2). The customer id is always the JWT subject; the
 * {@code usr.customers} row is created on the first call (documentations/06 MVP-ADR-10 amendment).
 * Contact details are read from the auth module and masked here.
 */
@Service
public class CustomerProfileService {

  static final String LOCKED =
      "The profile cannot change while a KYC application is under review or approved";

  private final CustomerRepository customers;
  private final KycApplicationRepository applications;
  private final UserContacts contacts;
  private final AuditService audit;
  private final BusinessTimeProperties businessTime;
  private final Clock clock;

  public CustomerProfileService(
      CustomerRepository customers,
      KycApplicationRepository applications,
      UserContacts contacts,
      AuditService audit,
      BusinessTimeProperties businessTime,
      Clock clock) {
    this.customers = customers;
    this.applications = applications;
    this.contacts = contacts;
    this.audit = audit;
    this.businessTime = businessTime;
    this.clock = clock;
  }

  @Transactional
  public CustomerProfile profile(UUID customerId) {
    customers.insertIfAbsent(customerId);
    return read(customerId);
  }

  /**
   * Sets the date of birth and the country. Both are locked from submit onward, while an
   * application is {@code PENDING_REVIEW} or {@code APPROVED}, and by a copied legal name: {@code
   * INVALID_KYC_STATE} (owner, 2026-11-03; MVP-ADR-10 amendment). A rejection unlocks them. The
   * lock is read from the applications in the database, never from the request.
   *
   * <p>The profile row is locked first, so this serialises with a submit and an approval, which
   * both lock the row before they change the application, and the check below reads what they
   * committed. V8's trigger refuses the same change as the last guard. Audited without the values.
   */
  // READ COMMITTED is what V8's profile lock relies on (N4); set here so it never depends on a
  // default.
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CustomerProfile updateProfile(
      UUID customerId, LocalDate dateOfBirth, String countryCode, InetAddress clientIp) {
    LocalDate checkedDate;
    String checkedCountry;
    try {
      checkedDate =
          CustomerProfileRules.requireDateOfBirth(
              dateOfBirth, LocalDate.ofInstant(clock.instant(), businessTime.businessZone()));
      checkedCountry = CustomerProfileRules.requireCountryCode(countryCode);
    } catch (InvalidKycDetailsException e) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
    }
    customers.insertIfAbsent(customerId);
    CustomerRow locked = customers.findForUpdate(customerId).orElseThrow(ApiException::notFound);
    if (locked.firstName() != null || applications.locksProfile(customerId)) {
      throw new ApiException(ErrorCode.INVALID_KYC_STATE, LOCKED);
    }
    customers.updateProfile(customerId, checkedDate, checkedCountry);
    audit.record(UserAudit.profileUpdated(customerId, clientIp));
    return read(customerId);
  }

  // A valid session always has its auth user, so a missing one is the generic 404 and not a 500.
  private CustomerProfile read(UUID customerId) {
    CustomerRow row = customers.find(customerId).orElseThrow(ApiException::notFound);
    ContactDetails contact = contacts.find(customerId).orElseThrow(ApiException::notFound);
    return new CustomerProfile(
        Masking.email(contact.email()),
        Masking.phone(contact.phoneNumber()),
        row.firstName(),
        row.lastName(),
        row.dateOfBirth(),
        row.countryCode());
  }
}
