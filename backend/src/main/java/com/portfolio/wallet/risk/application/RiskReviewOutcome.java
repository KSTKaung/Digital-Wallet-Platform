package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.text.InvalidTextException;
import com.portfolio.wallet.risk.domain.ReviewOutcome;
import com.portfolio.wallet.risk.domain.StaffRejectionCategory;
import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of a held transfer to record with {@link RiskReviewService#record}, for callers
 * outside the risk module (the {@code transfer} module in PR 5a, {@code admin} in 5b), which may
 * not use {@code risk.domain}. Each factory is one case of the seven of documentations/06
 * MVP-ADR-24 amendment "Review outcomes" (D2) and builds the domain's {@link ReviewOutcome}, so an
 * invalid combination cannot be constructed. {@link #toString()} leaves the actor and the reason
 * out.
 */
public final class RiskReviewOutcome {

  private final ReviewOutcome outcome;

  private RiskReviewOutcome(ReviewOutcome outcome) {
    this.outcome = outcome;
  }

  /** Case 1: a compliance officer approved. */
  public static RiskReviewOutcome staffApproval(UUID reviewer) {
    return new RiskReviewOutcome(ReviewOutcome.staffApproval(reviewer));
  }

  /**
   * Case 2: a compliance officer rejected with their own reason, under the single staff category
   * {@code STAFF_REJECTION} (S1). The reason follows the KYC reason text rules (MVP-ADR-10), at
   * most 500 characters, and is stored normalised; it is staff-only.
   *
   * @throws ApiException {@code VALIDATION_ERROR} naming the field, never the value
   */
  public static RiskReviewOutcome staffRejection(UUID reviewer, String reason) {
    Objects.requireNonNull(reviewer, "reviewer");
    try {
      return new RiskReviewOutcome(
          ReviewOutcome.staffRejection(reviewer, StaffRejectionCategory.STAFF_REJECTION, reason));
    } catch (InvalidTextException e) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
    }
  }

  /** Case 3: an approval by {@code reviewer} found the named wallet frozen or closed. */
  public static RiskReviewOutcome systemRejection(UUID reviewer, WalletUnavailability wallet) {
    Objects.requireNonNull(wallet, "wallet");
    return new RiskReviewOutcome(ReviewOutcome.systemRejection(reviewer, wallet.toDomain()));
  }

  /** Case 4: the sender cancelled before the deadline. */
  public static RiskReviewOutcome customerCancel(UUID sender) {
    return new RiskReviewOutcome(ReviewOutcome.customerCancel(sender));
  }

  /** Case 5: the scheduled job expired the transfer. */
  public static RiskReviewOutcome expiredByJob() {
    return new RiskReviewOutcome(ReviewOutcome.expiredByJob());
  }

  /** Case 6: an approval by {@code reviewer} found the deadline passed. */
  public static RiskReviewOutcome expiredOnLateApproval(UUID reviewer) {
    return new RiskReviewOutcome(ReviewOutcome.expiredOnLateApproval(reviewer));
  }

  /**
   * Case 6: a rejection by {@code reviewer} found the deadline passed. Whatever reason the reviewer
   * typed is not stored (D2), so none is taken.
   */
  public static RiskReviewOutcome expiredOnLateRejection(UUID reviewer) {
    return new RiskReviewOutcome(ReviewOutcome.expiredOnLateRejection(reviewer));
  }

  /** Case 7: a cancel by the sender found the deadline passed. */
  public static RiskReviewOutcome expiredOnLateCancel(UUID sender) {
    return new RiskReviewOutcome(ReviewOutcome.expiredOnLateCancel(sender));
  }

  ReviewOutcome toDomain() {
    return outcome;
  }

  /** The case only: the actor and the reviewer's text stay out of logs. */
  @Override
  public String toString() {
    return "RiskReviewOutcome[" + outcome.reviewCase() + "]";
  }
}
