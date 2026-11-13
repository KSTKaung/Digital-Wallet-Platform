package com.portfolio.wallet.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.RequestFingerprint;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The wallet's request adapter never prints the {@code Idempotency-Key} or the request hash. */
class IdempotentRequestTest {

  @Test
  void toStringNamesTheOperationOnly() {
    UUID key = UUID.randomUUID();
    UUID customer = UUID.randomUUID();
    IdempotentRequest request =
        new IdempotentRequest(
            key,
            RequestFingerprint.builder(
                    Operation.DEPOSIT, customer, Money.of(new BigDecimal("100"), Money.THB))
                .field("reference", "rent")
                .build());

    assertThat(request.toString())
        .isEqualTo("IdempotentRequest[operation=DEPOSIT]")
        .doesNotContain(key.toString(), request.requestHash(), customer.toString(), "rent");
  }

  @Test
  void mapsToTheEngineRequestOfTheSameKey() {
    UUID key = UUID.randomUUID();
    UUID customer = UUID.randomUUID();
    IdempotentRequest request =
        new IdempotentRequest(
            key,
            RequestFingerprint.builder(
                    Operation.WITHDRAWAL, customer, Money.of(new BigDecimal("100"), Money.THB))
                .build());

    var engineRequest = request.toEngineRequest();
    assertThat(engineRequest.customerId()).isEqualTo(customer);
    assertThat(engineRequest.operation()).isEqualTo("WITHDRAWAL");
    assertThat(engineRequest.key()).isEqualTo(key);
    assertThat(engineRequest.requestHash()).isEqualTo(request.requestHash());
  }
}
