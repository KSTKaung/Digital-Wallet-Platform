package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyHeaderTest {

  @Test
  void acceptsACanonicalUuidV4InEitherCase() {
    UUID key = UUID.randomUUID();
    assertThat(IdempotencyKeyHeader.parse(key.toString())).isEqualTo(key);
    assertThat(IdempotencyKeyHeader.parse(key.toString().toUpperCase(Locale.ROOT))).isEqualTo(key);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void missingKeyIsAValidationError(String value) {
    assertThatThrownBy(() -> IdempotencyKeyHeader.parse(value))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.getMessage()).isEqualTo("Idempotency-Key: header is required");
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1-1-1-1-1",
        "not-a-uuid",
        // version 1
        "6b1c4e0a-1d2b-11ee-8c90-0242ac120002",
        // version 4, but not the RFC 4122 variant
        "6b1c4e0a-1d2b-4fee-cc90-0242ac120002",
        "{6b1c4e0a-1d2b-4fee-8c90-0242ac120002}",
        " 6b1c4e0a-1d2b-4fee-8c90-0242ac120002",
        "6b1c4e0a1d2b4fee8c900242ac120002"
      })
  void malformedKeyIsAValidationErrorThatDoesNotEchoTheValue(String value) {
    assertThatThrownBy(() -> IdempotencyKeyHeader.parse(value))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.getMessage()).isEqualTo("Idempotency-Key: must be a UUID v4");
            });
  }
}
