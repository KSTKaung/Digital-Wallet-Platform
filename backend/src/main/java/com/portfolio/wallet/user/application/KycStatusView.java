package com.portfolio.wallet.user.application;

/**
 * {@code GET /kyc/status} (documentations/02 §2.2). {@code status} is {@link #NONE} and {@code
 * verificationLevel} null while the customer has no application.
 */
public record KycStatusView(String status, String verificationLevel, boolean walletCreated) {

  public static final String NONE = "NONE";
}
