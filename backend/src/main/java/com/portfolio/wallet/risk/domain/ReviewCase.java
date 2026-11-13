package com.portfolio.wallet.risk.domain;

/**
 * The seven review cases of documentations/06 MVP-ADR-24 amendment "Review outcomes" (owner,
 * 2026-11-06, D2), in the order of its table. V11 {@code ck_risk_reviews_outcome} accepts a row
 * only if it matches one of them.
 */
public enum ReviewCase {
  /** 1: {@code APPROVE} by staff. */
  STAFF_APPROVAL,
  /** 2: {@code REJECT} by staff, with the staff category and the reviewer's text. */
  STAFF_REJECTION,
  /** 3: an approval found a frozen or closed wallet: {@code REJECT} by the system. */
  SYSTEM_REJECTION,
  /** 4: {@code CANCEL} by the sender. */
  CUSTOMER_CANCEL,
  /** 5: {@code EXPIRE} by the scheduled job, with no request and no actor. */
  EXPIRED_BY_JOB,
  /** 6: an approval or rejection that found the deadline passed. */
  EXPIRED_ON_LATE_STAFF_ACTION,
  /** 7: a cancel that found the deadline passed. */
  EXPIRED_ON_LATE_CANCEL
}
