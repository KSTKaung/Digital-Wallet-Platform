package com.portfolio.wallet.auth.domain;

/** What a one-time code is for (the check constraint on {@code auth.otp_challenges}). */
public enum OtpPurpose {
  REGISTRATION,
  PASSWORD_RESET
}
