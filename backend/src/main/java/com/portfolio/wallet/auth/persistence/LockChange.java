package com.portfolio.wallet.auth.persistence;

/**
 * @param started whether the failure just recorded started a lock
 */
public record LockChange(boolean started) {}
