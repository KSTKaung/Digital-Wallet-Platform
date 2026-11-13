package com.portfolio.wallet.transfer.domain;

/**
 * The customer-facing category of a {@code REJECTED} transfer (documentations/06 MVP-ADR-24
 * amendment "Rejection categories (Q10)"; V11 {@code ck_transfers_rejection_reason_category}). The
 * only values a customer ever sees; never the reviewer's reason or a rule.
 */
public enum RejectionCategory {
  /** A compliance officer rejected the transfer (staff rejection, review case 2). */
  REVIEW_DECLINED,
  /** An approval found the sender's or the recipient's wallet frozen or closed (case 3). */
  WALLET_UNAVAILABLE
}
