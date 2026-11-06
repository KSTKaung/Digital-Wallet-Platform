package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The wallet's {@link StoredResponse} and {@link IdempotentResult} mirror the engine's types in
 * {@code common.idempotency}; they exist only so the PR 4b tests stay unchanged. These cases keep
 * the two copies from drifting: both accept and refuse exactly the same values.
 */
class StoredResponseCopiesTest {

  private static final BiConsumer<Integer, String> WALLET =
      (status, body) -> new StoredResponse(status, body);
  private static final BiConsumer<Integer, String> COMMON =
      (status, body) -> new com.portfolio.wallet.common.idempotency.StoredResponse(status, body);

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 250, 299})
  void bothAcceptEverySuccessStatus(int status) {
    assertThatCode(() -> WALLET.accept(status, "{}")).doesNotThrowAnyException();
    assertThatCode(() -> COMMON.accept(status, "{}")).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 199, 300, 403, 409, 500})
  void bothRefuseAnyOtherStatusTheSameWay(int status) {
    Throwable wallet = catchFrom(() -> WALLET.accept(status, "{}"));
    Throwable common = catchFrom(() -> COMMON.accept(status, "{}"));

    assertThat(wallet).isInstanceOf(IllegalArgumentException.class);
    assertThat(common).isInstanceOf(IllegalArgumentException.class);
    assertThat(wallet.getMessage()).isEqualTo(common.getMessage());
  }

  @Test
  void bothRefuseAMissingBody() {
    assertThatThrownBy(() -> WALLET.accept(201, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> COMMON.accept(201, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void bothResultsRequireAResponse() {
    assertThatThrownBy(() -> new IdempotentResult(null, true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new com.portfolio.wallet.common.idempotency.IdempotentResult(null, true))
        .isInstanceOf(NullPointerException.class);
    assertThat(new IdempotentResult(new StoredResponse(201, "{}"), true).replayed()).isTrue();
    assertThat(
            new com.portfolio.wallet.common.idempotency.IdempotentResult(
                    new com.portfolio.wallet.common.idempotency.StoredResponse(201, "{}"), true)
                .replayed())
        .isTrue();
  }

  @Test
  void bothHaveTheSameComponents() {
    assertThat(componentNames(StoredResponse.class))
        .containsExactly(
            componentNames(com.portfolio.wallet.common.idempotency.StoredResponse.class));
    assertThat(componentNames(IdempotentResult.class))
        .containsExactly(
            componentNames(com.portfolio.wallet.common.idempotency.IdempotentResult.class));
  }

  private static String[] componentNames(Class<?> record) {
    return java.util.Arrays.stream(record.getRecordComponents())
        .map(c -> c.getName())
        .toArray(String[]::new);
  }

  private static Throwable catchFrom(Runnable action) {
    try {
      action.run();
      return null;
    } catch (RuntimeException e) {
      return e;
    }
  }
}
