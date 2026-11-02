package com.portfolio.wallet.common.security;

/**
 * Records a {@code 403} refusal. A port, because {@code common} cannot depend on the audit module;
 * {@code audit.application} implements it. Web slices run without an implementation.
 */
public interface AccessDenialAuditor {

  /** Writes in its own transaction. {@link AccessDenials} keeps a failure from the response. */
  void record(AccessDenial denial);
}
