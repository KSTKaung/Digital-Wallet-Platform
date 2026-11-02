package com.portfolio.wallet.auth.domain;

/** Account status (documentations/08 §4A.2 Q7). Anything but {@link #ACTIVE} cannot log in. */
public enum UserStatus {
  PENDING_VERIFICATION,
  ACTIVE,
  FROZEN,
  SUSPENDED
}
