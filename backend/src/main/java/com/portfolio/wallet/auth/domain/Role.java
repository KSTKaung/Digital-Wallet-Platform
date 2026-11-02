package com.portfolio.wallet.auth.domain;

/**
 * Account role (the check constraint on {@code auth.users}); registration only creates customers.
 */
public enum Role {
  CUSTOMER,
  ADMIN,
  COMPLIANCE_OFFICER
}
