package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Owner decisions of 2026-11-04 on PR 4b step 8: {@code fundingSource} is refused on deposits (W3),
 * and a stored refusal has exactly one audit row (W5).
 */
class MoneyRefusalIT extends MoneyFlowSupport {

  // W3 ------------------------------------------------------------------------------------------

  @Test
  void anyFundingSourceValueIsAValidationErrorAndUsesNoKey() throws Exception {
    Account customer = withWallet();
    UUID key = UUID.randomUUID();

    for (String value :
        List.of(
            "{\"type\":\"CARD\",\"cardId\":\"4111111111111111\"}",
            "\"BANK\"",
            "42",
            "true",
            "[]",
            "{}")) {
      MockHttpServletResponse response =
          postMoney(
              DEPOSITS,
              "{\"amount\":100,\"currency\":\"THB\",\"fundingSource\":" + value + "}",
              customer.token(),
              key);
      assertThat(response.getStatus()).as(value).isEqualTo(400);
      assertThat(errorCode(response)).as(value).isEqualTo("VALIDATION_ERROR");
      assertThat(body(response).get("message").asString())
          .isEqualTo("fundingSource: is not supported");
      // The whole card number: a short fragment such as "4111" can occur by chance in the
      // nanosecond timestamp of the body.
      assertThat(response.getContentAsString())
          .doesNotContain("4111111111111111")
          .doesNotContain("BANK");
    }
    assertThat(idempotencyRecords(customer.id(), key)).isZero();
    assertThat(available(walletId(customer))).isEqualByComparingTo("0.00");

    // An explicit null counts as absent.
    MockHttpServletResponse withNull =
        postMoney(
            DEPOSITS,
            "{\"amount\":100,\"currency\":\"THB\",\"fundingSource\":null}",
            customer.token(),
            key);
    assertThat(withNull.getStatus()).isEqualTo(201);
  }

  // W5 ------------------------------------------------------------------------------------------

  private List<Map<String, Object>> refusalAudits(UUID customerId) {
    return jdbc.queryForList(
        "SELECT action, resource, result, new_value::text AS new_value FROM audit.audit_logs"
            + " WHERE actor_id = ? AND action IN ('DEPOSIT', 'WITHDRAWAL') AND result = 'FAILURE'",
        customerId);
  }

  @Test
  void aStoredRefusalHasOneFailureAuditRowAndAReplayAddsNone() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();

    assertThat(errorCode(withdraw(customer, "500", key))).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(errorCode(withdraw(customer, "500.00", key))).isEqualTo("INSUFFICIENT_BALANCE");

    List<Map<String, Object>> rows = refusalAudits(customer.id());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("action", "WITHDRAWAL")
        .containsEntry("resource", "WITHDRAWAL");
    assertThat((String) rows.get(0).get("new_value"))
        .contains("\"errorCode\": \"INSUFFICIENT_BALANCE\"")
        .contains("\"walletId\": \"" + wallet + "\"")
        .contains("\"amount\": \"500.00\"")
        .contains("\"currency\": \"THB\"");
  }

  @Test
  void everyStoredRefusalIsAuditedWithoutTheNote() throws Exception {
    Account customer = withWallet();
    funded(customer, "100");

    postMoney(
        DEPOSITS,
        "{\"amount\":100000.01,\"currency\":\"THB\",\"reference\":\"private-note-9\"}",
        customer.token(),
        UUID.randomUUID());
    assertThat(postAs(FREEZE, null, customer.token()).getStatus()).isEqualTo(200);
    withdraw(customer, "100", UUID.randomUUID());

    List<Map<String, Object>> rows = refusalAudits(customer.id());
    assertThat(rows)
        .extracting(row -> (String) row.get("new_value"))
        .anySatisfy(value -> assertThat(value).contains("SINGLE_LIMIT_EXCEEDED"))
        .anySatisfy(value -> assertThat(value).contains("WALLET_FROZEN"))
        .allSatisfy(value -> assertThat(value).doesNotContain("private-note-9"));
    assertThat(rows).hasSize(2);
  }

  @Test
  void errorsThatAreNotStoredAreNotAuditedAsMoneyFailures() throws Exception {
    Account customer = withWallet();
    withdraw(customer, "99.99", UUID.randomUUID());
    withdraw(customer, "100.001", UUID.randomUUID());
    Account noWallet = customer();
    withdraw(noWallet, "100", UUID.randomUUID());

    assertThat(refusalAudits(customer.id())).isEmpty();
    assertThat(refusalAudits(noWallet.id())).isEmpty();
  }

  /**
   * The race of D05: while this refusal waits to be stored, a request with the same key and body is
   * refused and stored first. This one then replays it and writes no second row.
   */
  @Test
  void aRefusalThatLosesTheRaceWritesNoAuditRow() throws Exception {
    Account customer = withWallet();
    UUID key = UUID.randomUUID();
    AtomicBoolean raced = new AtomicBoolean();
    doAnswer(
            invocation -> {
              if (raced.compareAndSet(false, true)) {
                CompletableFuture.runAsync(
                        () -> {
                          try {
                            movements.withdraw(
                                customer.id(), key, new BigDecimal("500"), "THB", null);
                          } catch (ApiException expected) {
                            // INSUFFICIENT_BALANCE, stored with its audit row.
                          }
                        })
                    .get(60, TimeUnit.SECONDS);
              }
              return invocation.callRealMethod();
            })
        .when(idempotencyRowsSpy)
        .insertIfAbsent(
            any(), anyString(), any(), anyString(), notNull(), any(), any(), any(), any());

    MockHttpServletResponse response = withdraw(customer, "500", key);

    assertThat(raced).isTrue();
    assertThat(errorCode(response)).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(refusalAudits(customer.id())).hasSize(1);
    assertThat(idempotencyRecords(customer.id(), key)).isEqualTo(1);
  }
}
