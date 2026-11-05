package com.portfolio.wallet.wallet.web;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The {@code Idempotency-Key} header of money POSTs (documentations/02 §1.3: required,
 * client-generated UUID v4). Controllers declare it with {@code @RequestHeader(name = NAME,
 * required = false) String} and call {@link #parse}, so a missing and a malformed key both give
 * {@code 400 VALIDATION_ERROR} with the same wording as the global handler's missing-header
 * message. Messages name the header, never its value.
 *
 * <p>Strict: only the canonical 36-character form of a version-4, RFC 4122 variant UUID, in either
 * case. {@link UUID#fromString} alone would also accept short forms such as {@code 1-1-1-1-1}.
 */
public final class IdempotencyKeyHeader {

  public static final String NAME = "Idempotency-Key";

  static final String REQUIRED = NAME + ": header is required";
  static final String INVALID = NAME + ": must be a UUID v4";

  private static final Pattern UUID_V4 =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

  private IdempotencyKeyHeader() {}

  /**
   * @throws ApiException {@code VALIDATION_ERROR} if the header is missing, blank or not a UUID v4
   */
  public static UUID parse(String value) {
    if (value == null || value.isBlank()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, REQUIRED);
    }
    if (!UUID_V4.matcher(value).matches()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID);
    }
    return UUID.fromString(value);
  }
}
