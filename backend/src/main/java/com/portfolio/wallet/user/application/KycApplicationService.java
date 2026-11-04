package com.portfolio.wallet.user.application;

import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.user.domain.DocumentReference;
import com.portfolio.wallet.user.domain.DocumentType;
import com.portfolio.wallet.user.domain.InvalidKycDetailsException;
import com.portfolio.wallet.user.domain.InvalidKycStateException;
import com.portfolio.wallet.user.domain.KycApplication;
import com.portfolio.wallet.user.domain.KycDetails;
import com.portfolio.wallet.user.domain.KycStatus;
import com.portfolio.wallet.user.domain.LegalName;
import com.portfolio.wallet.user.domain.VerificationLevel;
import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import com.portfolio.wallet.user.persistence.KycApplicationRepository;
import com.portfolio.wallet.user.persistence.KycApplicationRow;
import java.net.InetAddress;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's side of KYC (documentations/02 §2.2; doc 06 MVP-ADR-10 amendment): create, edit
 * and submit an application, and read it and the KYC status. Decisions are staff actions (PR 4a
 * step 3).
 *
 * <p>The customer id is always the JWT subject, never a client value. Every lookup is scoped by it,
 * so another customer's application id and a missing one give the same {@link
 * ApiException#notFound()} (MVP-ADR-19). State changes lock the application row first.
 */
@Service
public class KycApplicationService {

  private static final String ALREADY_ACTIVE = "An active KYC application already exists";
  static final String MISSING_DATE_OF_BIRTH =
      "dateOfBirth: must be set in the profile before submitting";
  static final String MISSING_COUNTRY_CODE =
      "countryCode: must be set in the profile before submitting";

  private final CustomerRepository customers;
  private final KycApplicationRepository applications;
  private final CustomerWallets wallets;
  private final AuditService audit;

  public KycApplicationService(
      CustomerRepository customers,
      KycApplicationRepository applications,
      CustomerWallets wallets,
      AuditService audit) {
    this.customers = customers;
    this.applications = applications;
    this.wallets = wallets;
    this.audit = audit;
  }

  /** A new {@code DRAFT}; {@code KYC_ALREADY_ACTIVE} if one is active, also in a race. */
  @Transactional
  public KycApplicationView create(
      UUID customerId, KycApplicationCommand command, InetAddress clientIp) {
    KycDetails details = toDetails(command);
    customers.insertIfAbsent(customerId);
    UUID id =
        applications
            .insertDraftIfNoneActive(
                customerId,
                details.verificationLevel().name(),
                details.documentType().name(),
                details.documentReference().value(),
                details.legalName().firstName(),
                details.legalName().lastName())
            .orElseThrow(() -> new ApiException(ErrorCode.KYC_ALREADY_ACTIVE, ALREADY_ACTIVE));
    audit.record(
        UserAudit.application(
            UserAudit.KYC_CREATE, customerId, id, null, KycStatus.DRAFT.name(), clientIp));
    return read(id, customerId);
  }

  /** Replaces a {@code DRAFT}'s content; {@code INVALID_KYC_STATE} in any other state. */
  @Transactional
  public KycApplicationView edit(
      UUID customerId, UUID applicationId, KycApplicationCommand command, InetAddress clientIp) {
    KycDetails details = toDetails(command);
    KycApplication application = lockOwned(applicationId, customerId);
    transition(application, a -> a.edit(details));
    applications.updateDraft(
        applicationId,
        customerId,
        details.verificationLevel().name(),
        details.documentType().name(),
        details.documentReference().value(),
        details.legalName().firstName(),
        details.legalName().lastName());
    audit.record(
        UserAudit.application(
            UserAudit.KYC_UPDATE,
            customerId,
            applicationId,
            KycStatus.DRAFT.name(),
            KycStatus.DRAFT.name(),
            clientIp));
    return read(applicationId, customerId);
  }

  /**
   * {@code DRAFT} to {@code PENDING_REVIEW}; {@code INVALID_KYC_STATE} in any other state. From
   * here on the profile's date of birth and country are locked (owner, 2026-11-03).
   *
   * <p>Lock order, the same as an approval's: the application row, then the profile row. With the
   * profile row held, a concurrent profile update either committed before (and this submit reads
   * its values) or waits and then finds the application {@code PENDING_REVIEW}; it can never change
   * the data under a submitted application. V8's trigger takes the same profile lock for the status
   * change as the last guard.
   */
  // READ COMMITTED is what V8's profile lock relies on (N4); set here so it never depends on a
  // default.
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public KycApplicationView submit(UUID customerId, UUID applicationId, InetAddress clientIp) {
    KycApplication application = lockOwned(applicationId, customerId);
    transition(application, KycApplication::submit);
    requireCompleteProfile(customerId);
    applications.submit(applicationId, customerId);
    audit.record(
        UserAudit.application(
            UserAudit.KYC_SUBMIT,
            customerId,
            applicationId,
            KycStatus.DRAFT.name(),
            KycStatus.PENDING_REVIEW.name(),
            clientIp));
    return read(applicationId, customerId);
  }

  @Transactional(readOnly = true)
  public KycApplicationView get(UUID customerId, UUID applicationId) {
    return read(applicationId, customerId);
  }

  /** The active application, or else the latest rejected one; {@code NONE} without either. */
  @Transactional(readOnly = true)
  public KycStatusView status(UUID customerId) {
    boolean walletCreated = wallets.exists(customerId);
    return applications
        .findCurrent(customerId)
        .map(row -> new KycStatusView(row.status(), row.verificationLevel(), walletCreated))
        .orElseGet(() -> new KycStatusView(KycStatusView.NONE, null, walletCreated));
  }

  /**
   * A submitted application is reviewed together with the profile, so the date of birth and the
   * country must be set first (owner, 2026-11-03); a draft may be created and edited without them.
   * Read under the profile row's lock, which the submit then holds until it commits.
   */
  private void requireCompleteProfile(UUID customerId) {
    CustomerRow profile = customers.findForUpdate(customerId).orElseThrow(ApiException::notFound);
    if (profile.dateOfBirth() == null) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, MISSING_DATE_OF_BIRTH);
    }
    if (profile.countryCode() == null) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, MISSING_COUNTRY_CODE);
    }
  }

  private KycApplication lockOwned(UUID applicationId, UUID customerId) {
    return applications
        .findOwnedForUpdate(applicationId, customerId)
        .map(KycApplicationService::toDomain)
        .orElseThrow(ApiException::notFound);
  }

  private static void transition(KycApplication application, Consumer<KycApplication> change) {
    try {
      change.accept(application);
    } catch (InvalidKycStateException e) {
      throw new ApiException(ErrorCode.INVALID_KYC_STATE, e.getMessage());
    }
  }

  private KycApplicationView read(UUID applicationId, UUID customerId) {
    return applications
        .findOwned(applicationId, customerId)
        .map(KycApplicationService::toView)
        .orElseThrow(ApiException::notFound);
  }

  /** The web layer has checked the shapes already; the domain is the final word. */
  static KycDetails toDetails(KycApplicationCommand command) {
    try {
      return new KycDetails(
          parse("verificationLevel", command.verificationLevel(), VerificationLevel::valueOf),
          parse("documentType", command.documentType(), DocumentType::valueOf),
          new DocumentReference(command.documentReference()),
          new LegalName(command.firstName(), command.lastName()));
    } catch (InvalidKycDetailsException e) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
    }
  }

  private static <E extends Enum<E>> E parse(
      String field, String value, Function<String, E> valueOf) {
    if (value == null) {
      throw new InvalidKycDetailsException(field, "must not be null");
    }
    try {
      return valueOf.apply(value);
    } catch (IllegalArgumentException e) {
      throw new InvalidKycDetailsException(field, "is not a documented value");
    }
  }

  static KycApplication toDomain(KycApplicationRow row) {
    return KycApplication.restore(
        row.id(),
        row.customerId(),
        new KycDetails(
            VerificationLevel.valueOf(row.verificationLevel()),
            DocumentType.valueOf(row.documentType()),
            new DocumentReference(row.documentReference()),
            new LegalName(row.firstName(), row.lastName())),
        KycStatus.valueOf(row.status()),
        row.reviewerId(),
        row.rejectionReason());
  }

  private static KycApplicationView toView(KycApplicationRow row) {
    return new KycApplicationView(
        row.id(),
        row.status(),
        row.verificationLevel(),
        row.documentType(),
        new DocumentReference(row.documentReference()).masked(),
        row.firstName(),
        row.lastName(),
        row.rejectionReason(),
        row.submittedAt(),
        row.decidedAt(),
        row.createdAt(),
        row.updatedAt());
  }
}
