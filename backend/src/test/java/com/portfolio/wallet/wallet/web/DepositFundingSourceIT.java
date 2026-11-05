package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code fundingSource} at the JSON level against the idempotency key (doc 02 §2.3, owner
 * 2026-11-04, W3: "an explicit {@code null} counts as absent"; doc 02 §1.4 request hash). An
 * explicit null and an absent field are the same request, so one replays the other rather than
 * giving {@code 409}; a value is a {@code 400} even on a key that is already used, and changes
 * nothing.
 */
class DepositFundingSourceIT extends MoneyFlowSupport {

  private static final String ABSENT = "{\"amount\":250,\"currency\":\"THB\"}";
  private static final String NULL =
      "{\"amount\":250.00,\"currency\":\"THB\",\"fundingSource\":null}";

  @Test
  void anExplicitNullAndAnAbsentFieldAreTheSameRequest() throws Exception {
    for (boolean nullFirst : new boolean[] {true, false}) {
      Account customer = withWallet();
      UUID wallet = walletId(customer);
      UUID key = UUID.randomUUID();

      MockHttpServletResponse first =
          postMoney(DEPOSITS, nullFirst ? NULL : ABSENT, customer.token(), key);
      MockHttpServletResponse second =
          postMoney(DEPOSITS, nullFirst ? ABSENT : NULL, customer.token(), key);

      assertThat(first.getStatus()).isEqualTo(201);
      assertThat(second.getStatus()).isEqualTo(201);
      assertThat(body(second)).isEqualTo(body(first));
      assertThat(available(wallet)).isEqualByComparingTo("250.00");
      assertThat(rows("wallet.deposits", wallet)).isEqualTo(1);
    }
  }

  @Test
  void aValueOnAUsedKeyIsAValidationErrorAndNeitherReplaysNorConflicts() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();
    MockHttpServletResponse first = postMoney(DEPOSITS, ABSENT, customer.token(), key);

    MockHttpServletResponse withValue =
        postMoney(
            DEPOSITS,
            "{\"amount\":250,\"currency\":\"THB\",\"fundingSource\":\"BANK\"}",
            customer.token(),
            key);

    assertThat(withValue.getStatus()).isEqualTo(400);
    assertThat(errorCode(withValue)).isEqualTo("VALIDATION_ERROR");
    // The key's answer is unchanged.
    assertThat(body(postMoney(DEPOSITS, NULL, customer.token(), key))).isEqualTo(body(first));
    assertThat(available(wallet)).isEqualByComparingTo("250.00");
  }

  /** The note is part of the request hash (doc 02 §2.3), so adding one to a used key is a 409. */
  @Test
  void addingANoteToAUsedKeyIsKeyReuse() throws Exception {
    Account customer = withWallet();
    UUID key = UUID.randomUUID();
    assertThat(postMoney(DEPOSITS, NULL, customer.token(), key).getStatus()).isEqualTo(201);

    MockHttpServletResponse withNote =
        postMoney(
            DEPOSITS,
            "{\"amount\":250,\"currency\":\"THB\",\"reference\":\"rent\",\"fundingSource\":null}",
            customer.token(),
            key);

    assertThat(withNote.getStatus()).isEqualTo(409);
    assertThat(errorCode(withNote)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(available(walletId(customer))).isEqualByComparingTo("250.00");
  }
}
