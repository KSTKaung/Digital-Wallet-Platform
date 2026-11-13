package com.portfolio.wallet.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The engine's value types and the shared startup checks. */
class IdempotencyTypesTest {

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private static final String HASH = "0123456789abcdef".repeat(4);

  // IdempotencyRequest ---------------------------------------------------------------------------

  @Test
  void aRequestNamesCustomerOperationKeyAndHash() {
    IdempotencyRequest request = new IdempotencyRequest(CUSTOMER, "TRANSFER", KEY, HASH);

    assertThat(request.customerId()).isEqualTo(CUSTOMER);
    assertThat(request.operation()).isEqualTo("TRANSFER");
    assertThat(request.key()).isEqualTo(KEY);
    assertThat(request.requestHash()).isEqualTo(HASH);
    assertThat(new IdempotencyRequest(CUSTOMER, "WITHDRAWAL", KEY, HASH).operation())
        .isEqualTo("WITHDRAWAL");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
        "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef",
        "0123456789abcdeg0123456789abcdef0123456789abcdef0123456789abcdef"
      })
  void aRequestHashIsSixtyFourLowerCaseHexCharacters(String hash) {
    assertThatThrownBy(() -> new IdempotencyRequest(CUSTOMER, "TRANSFER", KEY, hash))
        .isInstanceOf(IllegalArgumentException.class)
        // The message names the rule, never the value.
        .hasMessage("request hash must be 64 lower-case hex characters");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "transfer", "1TRANSFER", "TRANSFER-X", "OPERATION_NAME_TOO_LONG"})
  void anOperationIsAnUpperCaseNameThatFitsTheColumn(String operation) {
    assertThatThrownBy(() -> new IdempotencyRequest(CUSTOMER, operation, KEY, HASH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRequestNeedsEveryPart() {
    assertThatThrownBy(() -> new IdempotencyRequest(null, "TRANSFER", KEY, HASH))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotencyRequest(CUSTOMER, null, KEY, HASH))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotencyRequest(CUSTOMER, "TRANSFER", null, HASH))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotencyRequest(CUSTOMER, "TRANSFER", KEY, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aRequestPrintsNeitherKeyNorHashNorCustomer() {
    String text = new IdempotencyRequest(CUSTOMER, "TRANSFER", KEY, HASH).toString();

    assertThat(text).contains("TRANSFER");
    assertThat(text).doesNotContain(KEY.toString(), HASH, CUSTOMER.toString());
  }

  // StoredResponse and IdempotentResult ----------------------------------------------------------

  @Test
  void storedResponseIsASuccessWithABody() {
    assertThat(new StoredResponse(200, "{}").status()).isEqualTo(200);
    assertThat(new StoredResponse(299, "{}").status()).isEqualTo(299);
    assertThatThrownBy(() -> new StoredResponse(199, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StoredResponse(300, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StoredResponse(409, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StoredResponse(201, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotentResult(null, false))
        .isInstanceOf(NullPointerException.class);
  }

  // IdempotencySettings --------------------------------------------------------------------------

  @Test
  void aTtlIsAtLeastOneSecondAndAtMostThirtyDays() {
    String prefix = "app.transfer.idempotency.";
    assertThat(IdempotencySettings.requireValidTtl(prefix, Duration.ofSeconds(1)))
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(IdempotencySettings.requireValidTtl(prefix, Duration.ofDays(30)))
        .isEqualTo(Duration.ofDays(30));
    for (Duration invalid :
        new Duration[] {
          null,
          Duration.ZERO,
          Duration.ofHours(-1),
          Duration.ofSeconds(1).minusNanos(1),
          Duration.ofDays(30).plusNanos(1)
        }) {
      assertThatThrownBy(() -> IdempotencySettings.requireValidTtl(prefix, invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("app.transfer.idempotency.ttl must be at least 1 second and at most 30 days");
    }
    assertThat(IdempotencySettings.MIN_TTL).isEqualTo(Duration.ofSeconds(1));
    assertThat(IdempotencySettings.MAX_TTL).isEqualTo(Duration.ofDays(30));
  }

  @Test
  void aPurgeRunsAtAPositiveIntervalWithBatchesOfAtLeastOne() {
    String prefix = "app.transfer.idempotency.";
    IdempotencySettings.requireValidPurge(prefix, Duration.ofNanos(1), 1);
    assertThatThrownBy(() -> IdempotencySettings.requireValidPurge(prefix, null, 1))
        .hasMessage("app.transfer.idempotency.purge.interval must be positive");
    assertThatThrownBy(() -> IdempotencySettings.requireValidPurge(prefix, Duration.ZERO, 1))
        .hasMessage("app.transfer.idempotency.purge.interval must be positive");
    assertThatThrownBy(
            () -> IdempotencySettings.requireValidPurge(prefix, Duration.ofSeconds(-1), 1))
        .hasMessage("app.transfer.idempotency.purge.interval must be positive");
    assertThatThrownBy(() -> IdempotencySettings.requireValidPurge(prefix, Duration.ofHours(1), 0))
        .hasMessage("app.transfer.idempotency.purge.batch-size must be at least 1");
    assertThatThrownBy(() -> IdempotencySettings.requireValidTtl(null, Duration.ofHours(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> IdempotencySettings.requireValidPurge(null, Duration.ofHours(1), 1))
        .isInstanceOf(NullPointerException.class);
  }
}
