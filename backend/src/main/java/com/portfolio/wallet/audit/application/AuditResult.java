package com.portfolio.wallet.audit.application;

/** Outcome of an audited action (the check constraint on {@code audit.audit_logs}). */
public enum AuditResult {
  SUCCESS,
  FAILURE,
  DENIED
}
