package com.portfolio.wallet.transfer.web;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The {@code Idempotency-Key} header of {@code POST /transfers} (documentations/02 §1.3: required,
 * client-generated UUID v4, version and RFC variant checked; doc 06 MVP-ADR-06). The transfer
 * module's own copy of the wallet module's rule: a module's web layer is not shared. A missing and
 * a malformed key both give {@code 400 VALIDATION_ERROR} naming the header, never its value.
 */
final class IdempotencyKeyHeader {

  static final String NAME = "Idempotency-Key";

  static final String REQUIRED = NAME + ": header is required";
  static final String INVALID = NAME + ": must be a UUID v4";

  private static final Pattern UUID_V4 =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

  private IdempotencyKeyHeader() {}

  /**
   * @throws ApiException {@code VALIDATION_ERROR} if the header is missing, blank or not a UUID v4
   */
  static UUID parse(String value) {
    if (value == null || value.isBlank()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, REQUIRED);
    }
    if (!UUID_V4.matcher(value).matches()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID);
    }
    return UUID.fromString(value);
  }
}
