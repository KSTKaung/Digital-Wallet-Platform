package com.portfolio.wallet.wallet.persistence;

import java.time.Instant;

/**
 * One row of {@code wallet.idempotency_records} (V3), as read under its row lock.
 *
 * @param responseStatus null while the money transaction that inserted the row is still open; a
 *     committed row always has it, because the outcome is stored in the same transaction
 * @param responseBody the stored body as text; {@code jsonb} keeps the content, not the key order
 *     or whitespace
 * @param errorCode the {@code errorCode} of a stored rejection, null for a success
 * @param errorMessage the {@code message} of a stored rejection, null for a success
 */
public record IdempotencyRecordRow(
    String requestHash,
    Integer responseStatus,
    String responseBody,
    String errorCode,
    String errorMessage,
    Instant expiresAt) {}
