package com.portfolio.wallet.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.risk.domain.UnavailableWallet;
import com.portfolio.wallet.risk.persistence.RiskReviewRepository;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RiskReviewService} and {@link RiskReviewOutcome} with a mocked repository
 * (documentations/06 MVP-ADR-24 amendment "Review outcomes", D2): each case writes exactly its
 * columns, a duplicate is a distinct exception, the service joins the caller's transaction only,
 * and the staff reason follows the KYC reason text rules.
 */
class RiskReviewServiceTest {

  private static final UUID TRANSFER = UUID.fromString("00000000-0000-4000-8000-000000000010");
  private static final UUID ASSESSMENT = UUID.fromString("00000000-0000-4000-8000-000000000011");
  private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000012");

  private final RiskReviewRepository repository = mock(RiskReviewRepository.class);
  private final Runnable markRollbackOnly = mock(Runnable.class);
  private final RiskReviewService service = new RiskReviewService(repository, markRollbackOnly);

  @Test
  void case1StaffApproval() {
    service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.staffApproval(ACTOR));

    verify(repository)
        .insert(TRANSFER, ASSESSMENT, "APPROVE", "STAFF", "APPROVE", ACTOR, null, null, null);
  }

  @Test
  void case2StaffRejectionStoresTheCategoryAndTheNormalisedReason() {
    service.record(
        TRANSFER, ASSESSMENT, RiskReviewOutcome.staffRejection(ACTOR, "  Unclear purpose  "));

    verify(repository)
        .insert(
            TRANSFER,
            ASSESSMENT,
            "REJECT",
            "STAFF",
            "REJECT",
            ACTOR,
            "STAFF_REJECTION",
            "Unclear purpose",
            null);
  }

  @Test
  void case3SystemRejectionStoresTheWalletReason() {
    for (WalletUnavailability wallet : WalletUnavailability.values()) {
      service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.systemRejection(ACTOR, wallet));

      verify(repository)
          .insert(
              TRANSFER,
              ASSESSMENT,
              "REJECT",
              "SYSTEM",
              "APPROVE",
              ACTOR,
              null,
              null,
              wallet.name());
    }
  }

  @Test
  void case4CustomerCancel() {
    service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.customerCancel(ACTOR));

    verify(repository)
        .insert(TRANSFER, ASSESSMENT, "CANCEL", "CUSTOMER", "CANCEL", ACTOR, null, null, null);
  }

  @Test
  void case5ExpiryByTheJobHasNoRequestAndNoActor() {
    service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.expiredByJob());

    verify(repository)
        .insert(
            TRANSFER,
            ASSESSMENT,
            "EXPIRE",
            "SYSTEM",
            null,
            null,
            null,
            null,
            "REVIEW_DEADLINE_PASSED");
  }

  @Test
  void case6LateStaffActions() {
    service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.expiredOnLateApproval(ACTOR));
    service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.expiredOnLateRejection(ACTOR));

    verify(repository)
        .insert(
            TRANSFER,
            ASSESSMENT,
            "EXPIRE",
            "SYSTEM",
            "APPROVE",
            ACTOR,
            null,
            null,
            "REVIEW_DEADLINE_PASSED");
    verify(repository)
        .insert(
            TRANSFER,
            ASSESSMENT,
            "EXPIRE",
            "SYSTEM",
            "REJECT",
            ACTOR,
            null,
            null,
            "REVIEW_DEADLINE_PASSED");
  }

  @Test
  void case7LateCancel() {
    service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.expiredOnLateCancel(ACTOR));

    verify(repository)
        .insert(
            TRANSFER,
            ASSESSMENT,
            "EXPIRE",
            "SYSTEM",
            "CANCEL",
            ACTOR,
            null,
            null,
            "REVIEW_DEADLINE_PASSED");
  }

  @Test
  void aSecondReviewOfTheTransferIsADistinctException() {
    doThrow(new DuplicateKeyException("uq_risk_reviews_transfer"))
        .when(repository)
        .insert(any(), any(), any(), any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.expiredByJob()))
        .isInstanceOf(ReviewAlreadyRecordedException.class)
        .hasMessageNotContaining(TRANSFER.toString());
    // The caller's transaction cannot commit after this, even if the caller catches it.
    verify(markRollbackOnly).run();
  }

  @Test
  void anyOtherDatabaseFailurePropagatesUnchanged() {
    DataIntegrityViolationException mismatch =
        new DataIntegrityViolationException("fk_risk_reviews_assessment");
    doThrow(mismatch)
        .when(repository)
        .insert(any(), any(), any(), any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.record(TRANSFER, ASSESSMENT, RiskReviewOutcome.expiredByJob()))
        .isSameAs(mismatch);
    verifyNoInteractions(markRollbackOnly);
  }

  @Test
  void itJoinsTheCallersTransactionOnly() throws NoSuchMethodException {
    Method record =
        RiskReviewService.class.getMethod(
            "record", UUID.class, UUID.class, RiskReviewOutcome.class);

    assertThat(record.getAnnotation(Transactional.class).propagation())
        .isEqualTo(Propagation.MANDATORY);
  }

  @Test
  void theSpringConstructorNeedsARepository() {
    assertThatThrownBy(() -> new RiskReviewService(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void missingArgumentsAreRefusedBeforeAnyWrite() {
    RiskReviewOutcome outcome = RiskReviewOutcome.expiredByJob();
    assertThatThrownBy(() -> service.record(null, ASSESSMENT, outcome))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.record(TRANSFER, null, outcome))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.record(TRANSFER, ASSESSMENT, null))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(repository);
  }

  // RiskReviewOutcome ---------------------------------------------------------------------------

  @Test
  void anInvalidStaffReasonIsAValidationErrorNamingTheFieldOnly() {
    assertThatThrownBy(() -> RiskReviewOutcome.staffRejection(ACTOR, "secret\u0007text"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.getMessage())
                  .isEqualTo("reason: must not contain control or format characters");
            });
    assertThatThrownBy(() -> RiskReviewOutcome.staffRejection(ACTOR, "x".repeat(501)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getMessage()).isEqualTo("reason: must have at most 500 characters"));
    assertThatThrownBy(() -> RiskReviewOutcome.staffRejection(ACTOR, null))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void factoriesRefuseMissingActors() {
    assertThatThrownBy(() -> RiskReviewOutcome.staffApproval(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RiskReviewOutcome.staffRejection(null, "Unclear purpose"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                RiskReviewOutcome.systemRejection(null, WalletUnavailability.SENDER_WALLET_CLOSED))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RiskReviewOutcome.systemRejection(ACTOR, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RiskReviewOutcome.customerCancel(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RiskReviewOutcome.expiredOnLateApproval(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RiskReviewOutcome.expiredOnLateRejection(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RiskReviewOutcome.expiredOnLateCancel(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void toStringNamesTheCaseOnly() {
    assertThat(RiskReviewOutcome.staffRejection(ACTOR, "Unclear purpose").toString())
        .isEqualTo("RiskReviewOutcome[STAFF_REJECTION]");
  }

  @Test
  void everyWalletUnavailabilityMapsToItsDomainWallet() {
    for (WalletUnavailability wallet : WalletUnavailability.values()) {
      assertThat(wallet.toDomain().name()).isEqualTo(wallet.name());
    }
    assertThat(WalletUnavailability.values()).hasSameSizeAs(UnavailableWallet.values());
  }
}
