package com.portfolio.wallet.common.idempotency;

import java.time.Instant;

/**
 * One idempotency record as a module's {@link IdempotencyStore} reads it. Each module's row type
 * implements this; the engine only reads it.
 */
public interface IdempotencyRecord {

  /** 64 lower-case hex characters. */
  String requestHash();

  /**
   * Null while the money transaction that inserted the row is still open; a committed row always
   * has it, because the outcome is stored in the same transaction.
   */
  Integer responseStatus();

  /** The stored body as text; {@code jsonb} keeps the content, not the key order or whitespace. */
  String responseBody();

  /** The {@code errorCode} of a stored rejection, null for a success. */
  String errorCode();

  /** The {@code message} of a stored rejection, null for a success. */
  String errorMessage();

  Instant expiresAt();
}
