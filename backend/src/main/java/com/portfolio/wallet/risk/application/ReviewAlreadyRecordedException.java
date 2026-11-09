package com.portfolio.wallet.risk.application;

/**
 * The transfer already has a review row ({@code uq_risk_reviews_transfer}, V5): a second outcome
 * for one held transfer was about to be recorded. The caller is meant to have re-read the
 * transfer's status under its row lock and never get here (doc 08 §5 "From the V11 database
 * review"); this exception makes sure a duplicate is never silently swallowed. The caller's
 * transaction can no longer commit (PostgreSQL aborts it at the violation) and must roll back; the
 * caller maps it to its own response.
 */
public class ReviewAlreadyRecordedException extends RuntimeException {

  public ReviewAlreadyRecordedException() {
    super("the transfer already has a review outcome");
  }
}
