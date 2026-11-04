package com.portfolio.wallet.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The KYC state machine (documentations/02 §2.2; doc 06 MVP-ADR-10 amendment). */
class KycApplicationTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID REVIEWER = UUID.randomUUID();

  static KycDetails details(String reference) {
    return new KycDetails(
        VerificationLevel.BASIC,
        DocumentType.ID_CARD,
        new DocumentReference(reference),
        new LegalName("Somchai", "Jaidee"));
  }

  private static KycApplication in(KycStatus status) {
    return KycApplication.restore(
        UUID.randomUUID(), CUSTOMER, details("AB1234567"), status, null, null);
  }

  @Test
  void aDraftCanBeEditedAndKeepsItsState() {
    KycApplication application = in(KycStatus.DRAFT);
    KycDetails changed = details("ZZ9876543");

    application.edit(changed);

    assertThat(application.details()).isEqualTo(changed);
    assertThat(application.status()).isEqualTo(KycStatus.DRAFT);
  }

  @Test
  void submitMovesADraftToPendingReview() {
    KycApplication application = in(KycStatus.DRAFT);

    application.submit();

    assertThat(application.status()).isEqualTo(KycStatus.PENDING_REVIEW);
  }

  @ParameterizedTest
  @EnumSource(value = KycStatus.class, names = "DRAFT", mode = EnumSource.Mode.EXCLUDE)
  void onlyADraftCanBeEditedOrSubmitted(KycStatus status) {
    assertThatThrownBy(() -> in(status).edit(details("AB1234567")))
        .isInstanceOf(InvalidKycStateException.class)
        .hasMessageContaining(status.name());
    assertThatThrownBy(() -> in(status).submit()).isInstanceOf(InvalidKycStateException.class);
  }

  @Test
  void approveRecordsTheReviewer() {
    KycApplication application = in(KycStatus.PENDING_REVIEW);

    application.approve(REVIEWER);

    assertThat(application.status()).isEqualTo(KycStatus.APPROVED);
    assertThat(application.reviewerId()).isEqualTo(REVIEWER);
    assertThat(application.rejectionReason()).isNull();
  }

  @Test
  void rejectRecordsTheReviewerAndTheStrippedReason() {
    KycApplication application = in(KycStatus.PENDING_REVIEW);

    application.reject(REVIEWER, "  Document photo is unreadable  ");

    assertThat(application.status()).isEqualTo(KycStatus.REJECTED);
    assertThat(application.reviewerId()).isEqualTo(REVIEWER);
    assertThat(application.rejectionReason()).isEqualTo("Document photo is unreadable");
  }

  @ParameterizedTest
  @EnumSource(value = KycStatus.class, names = "PENDING_REVIEW", mode = EnumSource.Mode.EXCLUDE)
  void onlyAPendingApplicationCanBeDecided(KycStatus status) {
    assertThatThrownBy(() -> in(status).approve(REVIEWER))
        .isInstanceOf(InvalidKycStateException.class);
    assertThatThrownBy(() -> in(status).reject(REVIEWER, "reason"))
        .isInstanceOf(InvalidKycStateException.class);
  }

  @Test
  void theApplicantCannotDecideTheirOwnApplication() {
    assertThatThrownBy(() -> in(KycStatus.PENDING_REVIEW).approve(CUSTOMER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> in(KycStatus.PENDING_REVIEW).reject(CUSTOMER, "reason"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRejectionNeedsAReasonOfAtMost500Characters() {
    assertThatThrownBy(() -> in(KycStatus.PENDING_REVIEW).reject(REVIEWER, " "))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("reason: must not be blank");
    assertThatThrownBy(() -> in(KycStatus.PENDING_REVIEW).reject(REVIEWER, null))
        .isInstanceOf(InvalidKycDetailsException.class);
    assertThatThrownBy(() -> in(KycStatus.PENDING_REVIEW).reject(REVIEWER, "x".repeat(501)))
        .isInstanceOf(InvalidKycDetailsException.class);

    KycApplication atLimit = in(KycStatus.PENDING_REVIEW);
    atLimit.reject(REVIEWER, "x".repeat(500));
    assertThat(atLimit.status()).isEqualTo(KycStatus.REJECTED);
  }

  /** J6: a reason a person reads has a letter or digit, and no invisible format characters. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "...",
        "?!",
        "- / -",
        "Unread\u200Bable",
        "\u202Ereason",
        "zero\u200Cjoin",
        "zero\u200Djoin"
      })
  void aReasonWithoutALetterOrDigitOrWithFormatCharactersIsRefused(String reason) {
    KycApplication application = in(KycStatus.PENDING_REVIEW);

    assertThatThrownBy(() -> application.reject(REVIEWER, reason))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessageStartingWith("reason: ")
        .hasMessageNotContaining(reason);
    assertThat(application.status()).isEqualTo(KycStatus.PENDING_REVIEW);
  }

  @ParameterizedTest
  @ValueSource(strings = {"เอกสารไม่ชัดเจน", "2", "Photo unreadable!"})
  void aReasonWithALetterOrDigitInAnyScriptIsAccepted(String reason) {
    KycApplication application = in(KycStatus.PENDING_REVIEW);

    application.reject(REVIEWER, reason);

    assertThat(application.rejectionReason()).isEqualTo(reason);
  }

  @Test
  void aRejectionReasonMayNotRepeatTheDocumentReferenceInAnyCase() {
    KycApplication application = in(KycStatus.PENDING_REVIEW);

    assertThatThrownBy(() -> application.reject(REVIEWER, "Reference ab1234567 is not valid"))
        .isInstanceOf(InvalidKycDetailsException.class)
        .hasMessage("reason: must not contain the document reference")
        .hasMessageNotContaining("1234567");
    assertThat(application.status()).isEqualTo(KycStatus.PENDING_REVIEW);
  }

  @Test
  void statusesKnowWhetherTheyAreActiveOrFinal() {
    assertThat(KycStatus.DRAFT.isActive()).isTrue();
    assertThat(KycStatus.PENDING_REVIEW.isActive()).isTrue();
    assertThat(KycStatus.APPROVED.isActive()).isTrue();
    assertThat(KycStatus.REJECTED.isActive()).isFalse();
    assertThat(KycStatus.APPROVED.isFinal()).isTrue();
    assertThat(KycStatus.REJECTED.isFinal()).isTrue();
    assertThat(KycStatus.DRAFT.isFinal()).isFalse();
    assertThat(KycStatus.PENDING_REVIEW.isFinal()).isFalse();
  }

  @Test
  void toStringShowsNoKycContent() {
    KycApplication application = in(KycStatus.DRAFT);

    assertThat(application.toString())
        .contains(application.id().toString(), "DRAFT")
        .doesNotContain("AB1234567", "Somchai", "Jaidee");
    assertThat(application.details().toString())
        .contains("BASIC", "ID_CARD")
        .doesNotContain("AB1234567", "Somchai", "Jaidee");
    assertThat(application.customerId()).isEqualTo(CUSTOMER);
  }

  @Test
  void requiredPartsAreChecked() {
    assertThatThrownBy(
            () ->
                KycApplication.restore(null, CUSTOMER, details("A1"), KycStatus.DRAFT, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> in(KycStatus.DRAFT).edit(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> in(KycStatus.PENDING_REVIEW).approve(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new KycDetails(
                    null,
                    DocumentType.PASSPORT,
                    new DocumentReference("A1"),
                    new LegalName("a", "b")))
        .isInstanceOf(NullPointerException.class);
  }
}
