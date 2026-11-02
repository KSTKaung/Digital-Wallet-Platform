package com.portfolio.wallet.audit.application;

/** Who performed an audited action (the check constraint on {@code audit.audit_logs}). */
public enum ActorType {
  CUSTOMER,
  STAFF,
  SYSTEM,
  ANONYMOUS
}
