package com.portfolio.wallet.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Transfer invariants and state machine (documentations/06 MVP-ADR-24; doc 08 §4.5 test case 10).
 * The review duration is the documented 24 hours, passed in as the configuration would.
 */
class TransferTest {

  private static final Duration REVIEW = Duration.ofHours(24);
  private static final Instant HELD_AT = Instant.parse("2026-10-30T16:00:00Z");
  private static final Instant DEADLINE = HELD_AT.plus(REVIEW);
  private static final Instant BEFORE_DEADLINE = DEADLINE.minusNanos(1);
  private static final LocalDate LIMIT_DATE = LocalDate.of(2026, 10, 30);

  private static final UUID SENDER_CUSTOMER = UUID.randomUUID();
  private static final UUID RECEIVER_CUSTOMER = UUID.randomUUID();
  private static final UUID SENDER_WALLET = UUID.randomUUID();
  private static final UUID RECEIVER_WALLET = UUID.randomUUID();

  private static TransferDetails details(String amount, String description) {
    return new TransferDetails(
        UUID.randomUUID(),
        "TRF-0001",
        SENDER_CUSTOMER,
        RECEIVER_CUSTOMER,
        SENDER_WALLET,
        RECEIVER_WALLET,
        Money.of(amount, Money.THB),
        description,
        UUID.randomUUID(),
        LIMIT_DATE,
        UUID.randomUUID());
  }

  private static TransferDetails details() {
    return details("100.00", "rent");
  }

  private static Transfer held() {
    return Transfer.held(details(), HELD_AT, REVIEW);
  }

  // Creation ---------------------------------------------------------------------------------

  @Test
  void completedTransferIsPostedAndFinal() {
    Transfer transfer = Transfer.completed(details());

    assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(transfer.isLedgerPosted()).isTrue();
    assertThat(transfer.heldAt()).isEmpty();
    assertThat(transfer.reviewExpiresAt()).isEmpty();
    assertThat(transfer.details().limitDate()).isEqualTo(LIMIT_DATE);
  }

  @Test
  void heldTransferExpiresAfterTheReviewDuration() {
    Transfer transfer = held();

    assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING_REVIEW);
    assertThat(transfer.isLedgerPosted()).isFalse();
    assertThat(transfer.heldAt()).contains(HELD_AT);
    assertThat(transfer.reviewExpiresAt()).contains(Instant.parse("2026-10-31T16:00:00Z"));
    assertThat(transfer.decidedAt()).isEmpty();
  }

  @Test
  void heldNeedsAPositiveReviewDuration() {
    assertThatThrownBy(() -> Transfer.held(details(), HELD_AT, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Transfer.held(details(), HELD_AT, Duration.ofHours(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blockedTransferIsFinalAndNotPosted() {
    Transfer transfer = Transfer.blocked(details());

    assertThat(transfer.status()).isEqualTo(TransferStatus.BLOCKED);
    assertThat(transfer.isLedgerPosted()).isFalse();
  }

  @Test
  void selfTransferIsRejectedByCustomerOrWallet() {
    Money amount = Money.of("1.00", Money.THB);
    UUID key = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                new TransferDetails(
                    UUID.randomUUID(),
                    "TRF-1",
                    SENDER_CUSTOMER,
                    SENDER_CUSTOMER,
                    SENDER_WALLET,
                    RECEIVER_WALLET,
                    amount,
                    null,
                    key,
                    LIMIT_DATE,
                    UUID.randomUUID()))
        .isInstanceOf(SelfTransferException.class);
    assertThatThrownBy(
            () ->
                new TransferDetails(
                    UUID.randomUUID(),
                    "TRF-1",
                    SENDER_CUSTOMER,
                    RECEIVER_CUSTOMER,
                    SENDER_WALLET,
                    SENDER_WALLET,
                    amount,
                    null,
                    key,
                    LIMIT_DATE,
                    UUID.randomUUID()))
        .isInstanceOf(SelfTransferException.class);
  }

  @Test
  void riskAssessmentIsRequired() {
    assertThatThrownBy(
            () ->
                new TransferDetails(
                    UUID.randomUUID(),
                    "TRF-1",
                    SENDER_CUSTOMER,
                    RECEIVER_CUSTOMER,
                    SENDER_WALLET,
                    RECEIVER_WALLET,
                    Money.of("1.00", Money.THB),
                    null,
                    UUID.randomUUID(),
                    LIMIT_DATE,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("riskAssessmentId");
  }

  @Test
  void amountMustBePositive() {
    assertThatThrownBy(() -> details("0", null)).isInstanceOf(InvalidAmountException.class);
    assertThatThrownBy(() -> details("-1.00", null)).isInstanceOf(InvalidAmountException.class);
  }

  @Test
  void descriptionIsAtMost140Characters() {
    assertThat(details("1.00", "a".repeat(140)).description()).hasSize(140);
    assertThat(details("1.00", null).description()).isNull();
    // 140 Thai characters fit: the limit counts characters, not bytes.
    assertThat(details("1.00", "ก".repeat(140)).description()).hasSize(140);

    assertThatThrownBy(() -> details("1.00", "a".repeat(141)))
        .isInstanceOf(InvalidTransferDetailsException.class)
        .hasMessage("description is longer than 140 characters");
  }

  @Test
  void descriptionIsNeverInToString() {
    TransferDetails details = details("1.00", "secret note");

    assertThat(details.toString()).doesNotContain("secret note");
    assertThat(Transfer.completed(details).toString()).doesNotContain("secret note");
  }

  // Allowed transitions -----------------------------------------------------------------------

  @Test
  void approveBeforeTheDeadlineCompletes() {
    Transfer transfer = held();

    transfer.approve(BEFORE_DEADLINE);

    assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(transfer.isLedgerPosted()).isTrue();
    assertThat(transfer.decidedAt()).contains(BEFORE_DEADLINE);
    // The limit day stays the submission day (MVP-ADR-22).
    assertThat(transfer.details().limitDate()).isEqualTo(LIMIT_DATE);
  }

  @Test
  void rejectBeforeTheDeadlineStoresTheCategory() {
    Transfer transfer = held();

    transfer.reject(HELD_AT.plusSeconds(60), "RISK_REVIEW");

    assertThat(transfer.status()).isEqualTo(TransferStatus.REJECTED);
    assertThat(transfer.rejectionReasonCategory()).contains("RISK_REVIEW");
    assertThat(transfer.decidedAt()).contains(HELD_AT.plusSeconds(60));
    assertThat(transfer.isLedgerPosted()).isFalse();
  }

  @Test
  void rejectNeedsACategory() {
    Transfer transfer = held();

    assertThatThrownBy(() -> transfer.reject(HELD_AT, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> transfer.reject(HELD_AT, "x".repeat(41)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING_REVIEW);
  }

  @Test
  void cancelBeforeTheDeadline() {
    Transfer transfer = held();

    transfer.cancel(HELD_AT);

    assertThat(transfer.status()).isEqualTo(TransferStatus.CANCELLED);
    assertThat(transfer.decidedAt()).contains(HELD_AT);
  }

  @Test
  void expireAtAndAfterTheDeadline() {
    Transfer atDeadline = held();
    atDeadline.expire(DEADLINE);
    assertThat(atDeadline.status()).isEqualTo(TransferStatus.EXPIRED);
    assertThat(atDeadline.decidedAt()).contains(DEADLINE);

    Transfer later = held();
    later.expire(DEADLINE.plus(Duration.ofDays(3)));
    assertThat(later.status()).isEqualTo(TransferStatus.EXPIRED);
  }

  // Forbidden transitions ---------------------------------------------------------------------

  @ParameterizedTest(name = "{0} at the deadline")
  @MethodSource("reviewActions")
  void reviewActionsAtOrAfterTheDeadlineAreRefused(
      String action, BiConsumer<Transfer, Instant> apply) {
    for (Instant now : new Instant[] {DEADLINE, DEADLINE.plusSeconds(1)}) {
      Transfer transfer = held();

      assertThatThrownBy(() -> apply.accept(transfer, now))
          .isInstanceOf(TransferReviewExpiredException.class)
          .isInstanceOf(InvalidTransferStateException.class);
      assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING_REVIEW);
      assertThat(transfer.isReviewExpired(now)).isTrue();

      // The caller then expires it in the same transaction (MVP-ADR-24).
      transfer.expire(now);
      assertThat(transfer.status()).isEqualTo(TransferStatus.EXPIRED);
    }
  }

  @Test
  void expireBeforeTheDeadlineIsRefused() {
    Transfer transfer = held();

    assertThatThrownBy(() -> transfer.expire(BEFORE_DEADLINE))
        .isInstanceOf(InvalidTransferStateException.class)
        .isNotInstanceOf(TransferReviewExpiredException.class);
    assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING_REVIEW);
    assertThat(transfer.isReviewExpired(BEFORE_DEADLINE)).isFalse();
  }

  @ParameterizedTest(name = "{0} from {1}")
  @MethodSource("everyActionFromEveryFinalState")
  void finalStatesAreImmutable(
      String action, TransferStatus finalStatus, BiConsumer<Transfer, Instant> apply) {
    Transfer transfer = inState(finalStatus);
    var decidedAt = transfer.decidedAt();
    var category = transfer.rejectionReasonCategory();

    for (Instant now : new Instant[] {HELD_AT, BEFORE_DEADLINE, DEADLINE}) {
      assertThatThrownBy(() -> apply.accept(transfer, now))
          .isExactlyInstanceOf(InvalidTransferStateException.class);
    }
    assertThat(transfer.status()).isEqualTo(finalStatus);
    assertThat(transfer.decidedAt()).isEqualTo(decidedAt);
    assertThat(transfer.rejectionReasonCategory()).isEqualTo(category);
  }

  @ParameterizedTest
  @EnumSource(TransferStatus.class)
  void onlyPendingReviewIsNotFinal(TransferStatus status) {
    assertThat(status.isFinal()).isEqualTo(status != TransferStatus.PENDING_REVIEW);
  }

  @Test
  void restoreRequiresHoldTimesForPendingReview() {
    assertThatThrownBy(
            () ->
                Transfer.restore(
                    details(), TransferStatus.PENDING_REVIEW, null, null, null, null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Transfer.restore(
                    details(), TransferStatus.PENDING_REVIEW, HELD_AT, HELD_AT, null, null, 1))
        .isInstanceOf(IllegalArgumentException.class);

    Transfer restored =
        Transfer.restore(
            details(), TransferStatus.PENDING_REVIEW, HELD_AT, DEADLINE, null, null, 7);
    assertThat(restored.version()).isEqualTo(7);
    restored.approve(HELD_AT);
    assertThat(restored.status()).isEqualTo(TransferStatus.COMPLETED);
  }

  // Helpers ----------------------------------------------------------------------------------

  private static Stream<Arguments> reviewActions() {
    return Stream.of(
        Arguments.of("approve", (BiConsumer<Transfer, Instant>) Transfer::approve),
        Arguments.of(
            "reject", (BiConsumer<Transfer, Instant>) (t, now) -> t.reject(now, "RISK_REVIEW")),
        Arguments.of("cancel", (BiConsumer<Transfer, Instant>) Transfer::cancel));
  }

  private static Stream<Arguments> everyActionFromEveryFinalState() {
    Stream<Arguments> actions =
        Stream.concat(
            reviewActions(),
            Stream.of(Arguments.of("expire", (BiConsumer<Transfer, Instant>) Transfer::expire)));
    return actions.flatMap(
        action ->
            Stream.of(TransferStatus.values())
                .filter(TransferStatus::isFinal)
                .map(status -> Arguments.of(action.get()[0], status, action.get()[1])));
  }

  private static Transfer inState(TransferStatus status) {
    Transfer transfer = held();
    switch (status) {
      case COMPLETED -> transfer.approve(HELD_AT);
      case REJECTED -> transfer.reject(HELD_AT, "RISK_REVIEW");
      case CANCELLED -> transfer.cancel(HELD_AT);
      case EXPIRED -> transfer.expire(DEADLINE);
      case BLOCKED -> transfer = Transfer.blocked(details());
      case PENDING_REVIEW -> throw new IllegalArgumentException("not final");
    }
    return transfer;
  }
}
