package com.portfolio.wallet.wallet.application;

/** A wallet's status: {@code ACTIVE}, {@code FROZEN} or {@code CLOSED} (documentations/02 §2.3). */
public record WalletStatusView(String status) {}
