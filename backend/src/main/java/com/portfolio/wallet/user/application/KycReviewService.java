package com.portfolio.wallet.user.application;

import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.user.domain.DocumentReference;
import com.portfolio.wallet.user.domain.InvalidKycDetailsException;
import com.portfolio.wallet.user.domain.InvalidKycStateException;
import com.portfolio.wallet.user.domain.KycApplication;
import com.portfolio.wallet.user.domain.KycStatus;
import com.portfolio.wallet.user.domain.LegalName;
import com.portfolio.wallet.user.persistence.CustomerRepository;
import com.portfolio.wallet.user.persistence.CustomerRow;
import com.portfolio.wallet.user.persistence.KycApplicationRepository;
import com.portfolio.wallet.user.persistence.KycApplicationRow;
import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The compliance officer's side of KYC (documentations/02 §2.7; doc 06 MVP-ADR-10 amendment): list,
 * read, approve and reject submitted applications. The reviewer is always the JWT subject; nothing
 * a customer entered decides who may act.
 *
 * <p>Staff see submitted applications only. A {@code DRAFT} is the customer's unsent work, so for
 * staff it gives the same {@link ApiException#notFound()} as a missing id.
 *
 * <p>Reviewers decide on the application alone (owner, 2026-11-03, K4): its own content and the
 * date of birth and country copied onto it at submit. The current profile is never read for a
 * review, so a profile changed after a rejection cannot alter what a past decision was based on.
 *
 * <p>Lock order of a decision, the same for approve and reject: the application row ({@code FOR
 * UPDATE}), then (approval only) the customer's profile row. Every other path that locks both takes
 * them in this order or locks only one of them, so no cycle can form: a submit locks the
 * application and then the profile, the customer's profile update locks only the profile, and edit
 * locks only the application.
 *
 * <p>The customer's email is an outbox row written in the decision's transaction (MVP-ADR-25): it
 * commits or rolls back with the decision and is sent after commit; no address is looked up here.
 */
@Service
public class KycReviewService {

  /** The list filter; drafts are never listed. */
  static final Set<String> LISTABLE =
      Set.of(KycStatus.PENDING_REVIEW.name(), KycStatus.APPROVED.name(), KycStatus.REJECTED.name());

  static final String INVALID_STATUS = "status: must be PENDING_REVIEW, APPROVED or REJECTED";
  static final String OWN_APPLICATION = "A reviewer cannot decide their own application";
  static final String LEGAL_NAME_SET = "The customer's legal name is already set";
  static final String NOT_PENDING = "KYC application is no longer PENDING_REVIEW";

  private final CustomerRepository customers;
  private final KycApplicationRepository applications;
  private final CustomerWallets wallets;
  private final NotificationOutbox outbox;
  private final AuditService audit;

  public KycReviewService(
      CustomerRepository customers,
      KycApplicationRepository applications,
      CustomerWallets wallets,
      NotificationOutbox outbox,
      AuditService audit) {
    this.customers = customers;
    this.applications = applications;
    this.wallets = wallets;
    this.outbox = outbox;
    this.audit = audit;
  }

  /**
   * Submitted applications in {@code status}, oldest submission first, then by id (P2): the queue
   * is worked in the order customers submitted.
   *
   * @param status {@code PENDING_REVIEW}, {@code APPROVED} or {@code REJECTED}
   * @param page from 0; the web layer has checked the bounds (doc 02 §1.5)
   */
  @Transactional(readOnly = true)
  public KycReviewPage list(String status, int page, int size) {
    if (status == null || !LISTABLE.contains(status)) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID_STATUS);
    }
    long offset = (long) page * size;
    return new KycReviewPage(
        applications.findSubmittedPage(status, size, offset).stream()
            .map(KycReviewService::toSummary)
            .toList(),
        page,
        size,
        applications.countSubmitted(status));
  }

  @Transactional(readOnly = true)
  public KycReviewDetail get(UUID applicationId) {
    return detail(applicationId);
  }

  /**
   * {@code PENDING_REVIEW} to {@code APPROVED}, in one transaction (MVP-ADR-10; doc 08 §4B.4 AC 3):
   * the application is approved, then its legal name is copied to the profile, which locks the
   * profile for good (V8 accepts the name only from the application approved in this transaction),
   * the decision and the wallet creation are audited, the wallet and its ledger account are
   * created, and the customer's email is queued. Any failure rolls all of it back.
   *
   * <p>A concurrent second approval waits on the application lock, then finds the application
   * {@code APPROVED} and gets {@code INVALID_KYC_STATE}; {@code uq_wallets_customer} is the final
   * guard and gives the same code.
   */
  // READ COMMITTED is what V8's profile lock relies on (N4); set here so it never depends on a
  // default.
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public KycReviewDetail approve(UUID reviewerId, UUID applicationId, InetAddress clientIp) {
    KycApplication application = lockForDecision(reviewerId, applicationId);
    decide(application, a -> a.approve(reviewerId));
    UUID customerId = application.customerId();

    // The profile exists: the application's foreign key references it. Locked for the name copy;
    // only its legal name is read, never its date of birth or country (K4).
    CustomerRow profile =
        customers
            .findForUpdate(customerId)
            .orElseThrow(() -> new IllegalStateException("KYC application without a profile"));
    if (profile.firstName() != null) {
      throw new ApiException(ErrorCode.INVALID_KYC_STATE, LEGAL_NAME_SET);
    }
    if (applications.approve(applicationId, reviewerId) != 1) {
      throw new ApiException(ErrorCode.INVALID_KYC_STATE, NOT_PENDING);
    }
    LegalName name = application.details().legalName();
    if (customers.setLegalName(customerId, name.firstName(), name.lastName()) != 1) {
      throw new ApiException(ErrorCode.INVALID_KYC_STATE, LEGAL_NAME_SET);
    }
    audit.record(
        UserAudit.decision(
            UserAudit.KYC_APPROVE,
            reviewerId,
            applicationId,
            customerId,
            KycStatus.APPROVED.name(),
            clientIp));
    wallets.open(customerId, application.details().verificationLevel(), reviewerId, clientIp);
    outbox.enqueue(customerId, EmailKind.KYC_APPROVED);
    return detail(applicationId);
  }

  /**
   * {@code PENDING_REVIEW} to {@code REJECTED} with the reason the customer will see. The reason
   * must not contain the application's own document reference ({@code VALIDATION_ERROR} naming
   * {@code reason}, doc 08 §4B.2 Q13); the error never repeats either. The audit row records the
   * states, not the reason, and the queued email carries neither. The customer may then apply
   * again.
   */
  @Transactional
  public KycReviewDetail reject(
      UUID reviewerId, UUID applicationId, String reason, InetAddress clientIp) {
    KycApplication application = lockForDecision(reviewerId, applicationId);
    try {
      decide(application, a -> a.reject(reviewerId, reason));
    } catch (InvalidKycDetailsException e) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
    }
    if (applications.reject(applicationId, reviewerId, application.rejectionReason()) != 1) {
      throw new ApiException(ErrorCode.INVALID_KYC_STATE, NOT_PENDING);
    }
    audit.record(
        UserAudit.decision(
            UserAudit.KYC_REJECT,
            reviewerId,
            applicationId,
            application.customerId(),
            KycStatus.REJECTED.name(),
            clientIp));
    outbox.enqueue(application.customerId(), EmailKind.KYC_REJECTED);
    return detail(applicationId);
  }

  /**
   * The first lock of a decision. Staff and customer accounts are separate (MVP-ADR-12), so a
   * reviewer deciding their own application should be impossible; it is refused before the domain
   * rule and V8's check constraint would.
   */
  private KycApplication lockForDecision(UUID reviewerId, UUID applicationId) {
    KycApplication application =
        applications
            .findSubmittedForUpdate(applicationId)
            .map(KycApplicationService::toDomain)
            .orElseThrow(ApiException::notFound);
    if (application.customerId().equals(reviewerId)) {
      throw new ApiException(ErrorCode.FORBIDDEN, OWN_APPLICATION);
    }
    return application;
  }

  private static void decide(KycApplication application, Consumer<KycApplication> decision) {
    try {
      decision.accept(application);
    } catch (InvalidKycStateException e) {
      throw new ApiException(ErrorCode.INVALID_KYC_STATE, e.getMessage());
    }
  }

  // The application's own snapshot only (K4); the profile is not read.
  private KycReviewDetail detail(UUID applicationId) {
    KycApplicationRow row =
        applications.findSubmitted(applicationId).orElseThrow(ApiException::notFound);
    return new KycReviewDetail(
        row.id(),
        row.customerId(),
        row.status(),
        row.verificationLevel(),
        row.documentType(),
        new DocumentReference(row.documentReference()).masked(),
        row.firstName(),
        row.lastName(),
        row.dateOfBirth(),
        row.countryCode(),
        row.reviewerId(),
        row.rejectionReason(),
        row.submittedAt(),
        row.decidedAt(),
        row.createdAt(),
        row.updatedAt());
  }

  private static KycReviewSummary toSummary(KycApplicationRow row) {
    return new KycReviewSummary(
        row.id(),
        row.customerId(),
        row.status(),
        row.verificationLevel(),
        row.documentType(),
        new DocumentReference(row.documentReference()).masked(),
        new LegalName(row.firstName(), row.lastName()).masked(),
        row.submittedAt(),
        row.decidedAt(),
        row.createdAt());
  }
}
