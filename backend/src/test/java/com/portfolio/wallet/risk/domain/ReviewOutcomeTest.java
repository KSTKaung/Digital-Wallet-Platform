package com.portfolio.wallet.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.text.InvalidTextException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The seven review cases (documentations/06 MVP-ADR-24 amendment "Review outcomes", D2, S1): each
 * factory produces exactly its row of the D2 table, the columns of every other case stay empty, a
 * missing actor is refused, and the reviewer's reason follows the KYC reason text rules.
 */
class ReviewOutcomeTest {

  private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final String REASON = "Purpose of the transfer could not be confirmed";

  /** One row of the D2 table: what each column must hold. */
  record Row(
      ReviewCase reviewCase,
      ReviewAction action,
      DecisionSource decidedBy,
      RequestedAction requested,
      boolean actor,
      boolean staffReason,
      SystemReason systemReason) {}

  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of(
            (Supplier<ReviewOutcome>) () -> ReviewOutcome.staffApproval(ACTOR),
            new Row(
                ReviewCase.STAFF_APPROVAL,
                ReviewAction.APPROVE,
                DecisionSource.STAFF,
                RequestedAction.APPROVE,
                true,
                false,
                null)),
        Arguments.of(
            (Supplier<ReviewOutcome>)
                () ->
                    ReviewOutcome.staffRejection(
                        ACTOR, StaffRejectionCategory.STAFF_REJECTION, REASON),
            new Row(
                ReviewCase.STAFF_REJECTION,
                ReviewAction.REJECT,
                DecisionSource.STAFF,
                RequestedAction.REJECT,
                true,
                true,
                null)),
        Arguments.of(
            (Supplier<ReviewOutcome>)
                () ->
                    ReviewOutcome.systemRejection(ACTOR, UnavailableWallet.RECIPIENT_WALLET_CLOSED),
            new Row(
                ReviewCase.SYSTEM_REJECTION,
                ReviewAction.REJECT,
                DecisionSource.SYSTEM,
                RequestedAction.APPROVE,
                true,
                false,
                SystemReason.RECIPIENT_WALLET_CLOSED)),
        Arguments.of(
            (Supplier<ReviewOutcome>) () -> ReviewOutcome.customerCancel(ACTOR),
            new Row(
                ReviewCase.CUSTOMER_CANCEL,
                ReviewAction.CANCEL,
                DecisionSource.CUSTOMER,
                RequestedAction.CANCEL,
                true,
                false,
                null)),
        Arguments.of(
            (Supplier<ReviewOutcome>) ReviewOutcome::expiredByJob,
            new Row(
                ReviewCase.EXPIRED_BY_JOB,
                ReviewAction.EXPIRE,
                DecisionSource.SYSTEM,
                null,
                false,
                false,
                SystemReason.REVIEW_DEADLINE_PASSED)),
        Arguments.of(
            (Supplier<ReviewOutcome>) () -> ReviewOutcome.expiredOnLateApproval(ACTOR),
            new Row(
                ReviewCase.EXPIRED_ON_LATE_STAFF_ACTION,
                ReviewAction.EXPIRE,
                DecisionSource.SYSTEM,
                RequestedAction.APPROVE,
                true,
                false,
                SystemReason.REVIEW_DEADLINE_PASSED)),
        Arguments.of(
            (Supplier<ReviewOutcome>) () -> ReviewOutcome.expiredOnLateRejection(ACTOR),
            new Row(
                ReviewCase.EXPIRED_ON_LATE_STAFF_ACTION,
                ReviewAction.EXPIRE,
                DecisionSource.SYSTEM,
                RequestedAction.REJECT,
                true,
                false,
                SystemReason.REVIEW_DEADLINE_PASSED)),
        Arguments.of(
            (Supplier<ReviewOutcome>) () -> ReviewOutcome.expiredOnLateCancel(ACTOR),
            new Row(
                ReviewCase.EXPIRED_ON_LATE_CANCEL,
                ReviewAction.EXPIRE,
                DecisionSource.SYSTEM,
                RequestedAction.CANCEL,
                true,
                false,
                SystemReason.REVIEW_DEADLINE_PASSED)));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("cases")
  void eachFactoryBuildsExactlyItsCase(Supplier<ReviewOutcome> factory, Row row) {
    ReviewOutcome outcome = factory.get();

    assertThat(outcome.reviewCase()).isEqualTo(row.reviewCase());
    assertThat(outcome.action()).isEqualTo(row.action());
    assertThat(outcome.decidedBy()).isEqualTo(row.decidedBy());
    assertThat(outcome.requestedAction()).isEqualTo(Optional.ofNullable(row.requested()));
    assertThat(outcome.actorUserId())
        .isEqualTo(row.actor() ? Optional.of(ACTOR) : Optional.empty());
    if (row.staffReason()) {
      assertThat(outcome.reasonCategory()).contains(StaffRejectionCategory.STAFF_REJECTION);
      assertThat(outcome.reason()).contains(REASON);
    } else {
      assertThat(outcome.reasonCategory()).isEmpty();
      assertThat(outcome.reason()).isEmpty();
    }
    assertThat(outcome.systemReason()).isEqualTo(Optional.ofNullable(row.systemReason()));
    // V11 ck_risk_reviews_system_decision: exactly a system decision has a system reason.
    assertThat(outcome.systemReason().isPresent())
        .isEqualTo(outcome.decidedBy() == DecisionSource.SYSTEM);
    // V11 ck_risk_reviews_staff_rejection: exactly a staff REJECT has the category and the text.
    boolean staffReject =
        outcome.action() == ReviewAction.REJECT && outcome.decidedBy() == DecisionSource.STAFF;
    assertThat(outcome.reasonCategory().isPresent()).isEqualTo(staffReject);
    assertThat(outcome.reason().isPresent()).isEqualTo(staffReject);
  }

  @Test
  void everyCaseHasAFactory() {
    assertThat(cases().map(a -> ((Row) a.get()[1]).reviewCase()).distinct())
        .containsExactlyInAnyOrder(ReviewCase.values());
  }

  @Test
  void aSystemRejectionCarriesEachWalletReasonAndNeverTheDeadline() {
    for (UnavailableWallet wallet : UnavailableWallet.values()) {
      ReviewOutcome outcome = ReviewOutcome.systemRejection(ACTOR, wallet);

      assertThat(outcome.systemReason()).contains(wallet.systemReason());
      assertThat(outcome.systemReason())
          .isNotEqualTo(Optional.of(SystemReason.REVIEW_DEADLINE_PASSED));
    }
    assertThat(UnavailableWallet.values())
        .extracting(UnavailableWallet::systemReason)
        .doesNotContain(SystemReason.REVIEW_DEADLINE_PASSED)
        .doesNotHaveDuplicates()
        .hasSize(SystemReason.values().length - 1);
  }

  @Test
  void everyCaseWithAnActorRefusesAMissingOne() {
    assertThatThrownBy(() -> ReviewOutcome.staffApproval(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                ReviewOutcome.staffRejection(null, StaffRejectionCategory.STAFF_REJECTION, REASON))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> ReviewOutcome.systemRejection(null, UnavailableWallet.SENDER_WALLET_FROZEN))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReviewOutcome.customerCancel(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReviewOutcome.expiredOnLateApproval(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReviewOutcome.expiredOnLateRejection(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReviewOutcome.expiredOnLateCancel(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aStaffRejectionNeedsACategoryAndAWallet() {
    assertThatThrownBy(() -> ReviewOutcome.staffRejection(ACTOR, null, REASON))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReviewOutcome.systemRejection(ACTOR, null))
        .isInstanceOf(NullPointerException.class);
  }

  // Reason text (MVP-ADR-10 KYC reason text rules, at most 500) ---------------------------------

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", "   ", "\t\n", "...", "!?", "a\u0007b", "a​b", "a‮b"})
  void anUnacceptableReasonIsRefused(String reason) {
    assertThatThrownBy(
            () ->
                ReviewOutcome.staffRejection(ACTOR, StaffRejectionCategory.STAFF_REJECTION, reason))
        .isInstanceOf(InvalidTextException.class)
        .satisfies(e -> assertThat(((InvalidTextException) e).field()).isEqualTo("reason"))
        .hasMessageStartingWith("reason: ");
  }

  @Test
  void aMissingReasonIsRefused() {
    assertThatThrownBy(
            () -> ReviewOutcome.staffRejection(ACTOR, StaffRejectionCategory.STAFF_REJECTION, null))
        .isInstanceOf(InvalidTextException.class)
        .hasMessage("reason: must not be blank");
  }

  @Test
  void aReasonOfAtMost500CharactersIsAcceptedAndStoredNormalised() {
    String max = "x".repeat(500);
    assertThat(
            ReviewOutcome.staffRejection(ACTOR, StaffRejectionCategory.STAFF_REJECTION, max)
                .reason())
        .contains(max);
    // Surrounding whitespace is removed and the text is NFC (e + combining acute -> é).
    assertThat(
            ReviewOutcome.staffRejection(
                    ACTOR, StaffRejectionCategory.STAFF_REJECTION, "  Café ok  ")
                .reason())
        .contains("Café ok");
    // Counted in code points: 500 characters outside the BMP fit.
    String astral = "😀".repeat(499) + "a";
    assertThat(
            ReviewOutcome.staffRejection(ACTOR, StaffRejectionCategory.STAFF_REJECTION, astral)
                .reason())
        .contains(astral);
  }

  @Test
  void aReasonOver500CharactersIsRefusedWithoutItsValue() {
    String tooLong = "y".repeat(501);

    assertThatThrownBy(
            () ->
                ReviewOutcome.staffRejection(
                    ACTOR, StaffRejectionCategory.STAFF_REJECTION, tooLong))
        .isInstanceOf(InvalidTextException.class)
        .hasMessage("reason: must have at most 500 characters");
  }

  @Test
  void toStringLeavesTheActorAndTheReasonOut() {
    ReviewOutcome outcome =
        ReviewOutcome.staffRejection(ACTOR, StaffRejectionCategory.STAFF_REJECTION, REASON);

    assertThat(outcome.toString())
        .isEqualTo("ReviewOutcome[STAFF_REJECTION]")
        .doesNotContain(ACTOR.toString())
        .doesNotContain("Purpose");
  }
}
