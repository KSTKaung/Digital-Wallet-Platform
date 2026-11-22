package com.portfolio.wallet.user.application;

import java.util.UUID;

/**
 * {@code GET /kyc/status} (documentations/02 §2.2). {@code applicationId} is the reported
 * application's id (owner, 2026-11-19: the SPA has no other way to find it after a reload). {@code
 * status} is {@link #NONE}, and {@code applicationId} and {@code verificationLevel} are null, while
 * the customer has no application.
 */
public record KycStatusView(
    String status, UUID applicationId, String verificationLevel, boolean walletCreated) {

  public static final String NONE = "NONE";
}
