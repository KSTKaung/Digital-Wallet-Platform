package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.idempotency.StoredResponse;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.transfer.domain.TransferRequestFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A refusal found by the pre-check, outside the engine, is stored for the key and replayed exactly
 * like one found under the locks (documentations/08 §5, step 2 notes; doc 06 MVP-ADR-06; code
 * review 2 of PR 5a step 4). A code that is not stored leaves nothing behind.
 */
class TransferRefusalStorageIT extends DatabaseIntegrationTest {

  @Autowired TransferIdempotency idempotency;

  private static TransferIdempotentRequest request(UUID sender, UUID key, String amount) {
    return new TransferIdempotentRequest(
        key,
        TransferRequestFingerprint.builder(sender, "12345678903", Money.of(amount, Money.THB))
            .description("rent")
            .build());
  }

  private int records(UUID sender, UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM transfer.idempotency_records"
            + " WHERE customer_id = ? AND idempotency_key = ?",
        Integer.class,
        sender,
        key);
  }

  @Test
  void aPrecheckRefusalIsStoredAndReplayedWithTheSameKey() {
    UUID sender = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    List<ErrorCode> hooked = new ArrayList<>();
    ApiException refusal =
        new ApiException(
            ErrorCode.INSUFFICIENT_BALANCE, "Available balance is lower than the requested amount");

    assertThatThrownBy(
            () ->
                idempotency.refuse(
                    request(sender, key, "100.00"), refusal, e -> hooked.add(e.errorCode())))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
    assertThat(hooked).containsExactly(ErrorCode.INSUFFICIENT_BALANCE);
    assertThat(records(sender, key)).isOne();

    // A retry with the same key and body is answered by the stored refusal, even after a top-up:
    // the transfer does not run, the hook does not run again.
    AtomicInteger runs = new AtomicInteger();
    assertThatThrownBy(
            () ->
                idempotency.execute(
                    request(sender, key, "100.00"),
                    () -> {
                      runs.incrementAndGet();
                      return new StoredResponse(201, "{}");
                    },
                    e -> hooked.add(e.errorCode())))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
              assertThat(e.getMessage())
                  .isEqualTo("Available balance is lower than the requested amount");
            });
    assertThatThrownBy(() -> idempotency.replayIfCompleted(request(sender, key, "100.00")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
    assertThat(runs).hasValue(0);
    assertThat(hooked).hasSize(1);
    // Another body with the key is 409, as for any stored outcome.
    assertThatThrownBy(() -> idempotency.replayIfCompleted(request(sender, key, "100.01")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @Test
  void aRefusalWhoseCodeIsNotStoredLeavesNoRecordAndTheKeyRunsFresh() {
    UUID sender = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    ApiException belowMinimum = new ApiException(ErrorCode.INVALID_AMOUNT, "amount: is below");

    assertThatThrownBy(
            () -> idempotency.refuse(request(sender, key, "0.50"), belowMinimum, e -> {}))
        .isSameAs(belowMinimum);
    assertThat(records(sender, key)).isZero();

    assertThat(
            idempotency
                .execute(request(sender, key, "0.50"), () -> new StoredResponse(201, "{}"))
                .replayed())
        .isFalse();
    assertThat(records(sender, key)).isOne();
  }
}
