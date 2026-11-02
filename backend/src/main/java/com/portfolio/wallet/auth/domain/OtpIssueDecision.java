package com.portfolio.wallet.auth.domain;

/** Whether a new challenge may be created; every refusal silently sends nothing. */
public enum OtpIssueDecision {
  ALLOWED,
  COOLDOWN,
  HOURLY_CAP,
  USED_UP_BLOCK
}
