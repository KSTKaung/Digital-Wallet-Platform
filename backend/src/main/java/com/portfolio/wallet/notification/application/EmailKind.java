package com.portfolio.wallet.notification.application;

/** What an email is for. This label, not the content, is what logs and audit rows record. */
public enum EmailKind {
  OTP,
  ACCOUNT_LOCKED,
  EXISTING_ACCOUNT_NOTICE,
  KYC_APPROVED,
  KYC_REJECTED,
  WALLET_FROZEN,
  WALLET_UNFROZEN,
  // Money emails (documentations/06 MVP-ADR-24 amendment "Emails (Q9)", MVP-ADR-25; V11). There is
  // deliberately no kind for a risk BLOCK, and none that tells a receiver about a transfer before
  // it completes.
  DEPOSIT_COMPLETED,
  WITHDRAWAL_COMPLETED,
  /** To the sender, when a transfer completes. */
  TRANSFER_SENT,
  /** To the receiver, when a transfer completes; never earlier. */
  TRANSFER_RECEIVED,
  /** To the sender only, when a transfer is held for review. */
  TRANSFER_HELD,
  /** To the sender, when a held transfer is rejected (PR 5b). */
  TRANSFER_REJECTED,
  /** To the sender, when they cancel a held transfer. */
  TRANSFER_CANCELLED,
  /** To the sender, when a held transfer expires (the job, or an action after the deadline). */
  TRANSFER_EXPIRED
}
