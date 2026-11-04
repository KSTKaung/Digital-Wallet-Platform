package com.portfolio.wallet.notification.application;

/** What an email is for. This label, not the content, is what logs and audit rows record. */
public enum EmailKind {
  OTP,
  ACCOUNT_LOCKED,
  EXISTING_ACCOUNT_NOTICE,
  KYC_APPROVED,
  KYC_REJECTED,
  WALLET_FROZEN,
  WALLET_UNFROZEN
}
