package com.portfolio.wallet.user.domain;

/**
 * KYC level applied for (documentations/06 MVP-ADR-10); it sets the wallet tier on approval. The
 * check constraint on {@code usr.kyc_applications}.
 */
public enum VerificationLevel {
  BASIC,
  FULL
}
