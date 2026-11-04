package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.auth.domain.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * The customer's own wallet (documentations/02 §2.3; doc 06 MVP-ADR-10, -18, -21; doc 08 §4B.4 AC
 * 1, 9): no wallet before approval, the reads, money as JSON numbers with 2 decimals, the limits of
 * the wallet's tier from configuration, and the customer's own freeze.
 */
class WalletFlowIT extends WalletFlowSupport {

  private static final List<String> READS = List.of(WALLET, BALANCE, STATUS, LIMITS);

  // Before approval ------------------------------------------------------------------------------

  @Test
  void beforeApprovalEveryReadAndTheFreezeIsWalletNotFound() throws Exception {
    Account noKyc = customer();
    Account pending = customer();
    submitted(pending, "BASIC");

    for (Account account : List.of(noKyc, pending)) {
      for (String path : READS) {
        MockHttpServletResponse response = getAs(path, account.token());
        assertThat(response.getStatus()).as(path).isEqualTo(404);
        assertThat(errorCode(response)).as(path).isEqualTo("WALLET_NOT_FOUND");
      }
      MockHttpServletResponse freeze = postAs(FREEZE, null, account.token());
      assertThat(freeze.getStatus()).isEqualTo(404);
      assertThat(errorCode(freeze)).isEqualTo("WALLET_NOT_FOUND");
    }
    assertThat(stable(getAs(WALLET, noKyc.token())))
        .isEqualTo(stable(getAs(WALLET, pending.token())));
  }

  // Reads ----------------------------------------------------------------------------------------

  @Test
  void theWalletHasTheDocumentedFieldsWithMoneyAsNumbersAndNoInternalId() throws Exception {
    Account customer = withWallet();
    Map<String, Object> stored = walletOf(customer.id());

    MockHttpServletResponse response = getAs(WALLET, customer.token());

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = body(response);
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "walletNumber",
            "status",
            "tier",
            "availableBalance",
            "reservedBalance",
            "currency",
            "createdAt");
    assertThat(body.get("walletNumber").asString()).isEqualTo(stored.get("wallet_number"));
    assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(body.get("tier").asString()).isEqualTo("BASIC");
    assertThat(body.get("currency").asString()).isEqualTo("THB");
    assertThat(body.get("availableBalance").isNumber()).isTrue();
    assertThat(body.get("createdAt").asString()).endsWith("Z");
    String content = response.getContentAsString();
    assertThat(content)
        .contains("\"availableBalance\":0.00", "\"reservedBalance\":0.00")
        .doesNotContain(stored.get("id").toString(), customer.id().toString());
  }

  @Test
  void theBalanceIsReadFreshOnEveryCallAndNeverCached() throws Exception {
    Account customer = withWallet();
    assertThat(getAs(BALANCE, customer.token()).getContentAsString())
        .isEqualTo("{\"availableBalance\":0.00,\"reservedBalance\":0.00,\"currency\":\"THB\"}");
    // A snapshot change by a later money flow (PR 4b), made directly here.
    jdbc.update(
        "UPDATE wallet.wallets SET available_balance = 1234.5, reserved_balance = 10,"
            + " version = version + 1 WHERE customer_id = ?",
        customer.id());

    MockHttpServletResponse response = getAs(BALANCE, customer.token());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString())
        .isEqualTo("{\"availableBalance\":1234.50,\"reservedBalance\":10.00,\"currency\":\"THB\"}");
    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
  }

  @Test
  void theStatus() throws Exception {
    Account customer = withWallet();

    MockHttpServletResponse response = getAs(STATUS, customer.token());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"ACTIVE\"}");
  }

  /** The values are application.yaml's, i.e. MVP-ADR-18's, by the wallet's tier. */
  @Test
  void theLimitsOfABasicWallet() throws Exception {
    Account customer = withWallet("BASIC");

    MockHttpServletResponse response = getAs(LIMITS, customer.token());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"dailyTransfer\":100000.00,\"dailyWithdrawal\":50000.00,\"dailyDeposit\":200000.00,"
                + "\"singleTransfer\":50000.00,\"singleWithdrawal\":30000.00,"
                + "\"singleDeposit\":100000.00,\"minTransfer\":1.00,\"minWithdrawal\":100.00,"
                + "\"minDeposit\":100.00,\"currency\":\"THB\"}");
  }

  @Test
  void aVerifiedWalletHasTheHigherDailyTransferLimit() throws Exception {
    Account customer = withWallet("FULL");

    JsonNode body = body(getAs(LIMITS, customer.token()));

    assertThat(body(getAs(WALLET, customer.token())).get("tier").asString()).isEqualTo("VERIFIED");
    assertThat(body.get("dailyTransfer").decimalValue()).isEqualByComparingTo("500000.00");
    assertThat(body.get("singleTransfer").decimalValue()).isEqualByComparingTo("50000.00");
  }

  // The customer's own freeze -------------------------------------------------------------------

  @Test
  void aFreezeChangesTheWalletAndItsLedgerAccountTogetherAndIsAudited() throws Exception {
    Account customer = withWallet();
    Map<String, Object> before = walletOf(customer.id());
    UUID walletId = (UUID) before.get("id");
    String correlationId = UUID.randomUUID().toString();

    MockHttpServletResponse response =
        sendWithCorrelation(post(FREEZE), customer.token(), correlationId);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"FROZEN\"}");
    Map<String, Object> after = walletOf(customer.id());
    assertThat(after.get("status")).isEqualTo("FROZEN");
    assertThat(after.get("version")).isEqualTo((Long) before.get("version") + 1);
    assertThat(ledgerStatusOf(walletId)).isEqualTo("FROZEN");
    assertThat(getAs(STATUS, customer.token()).getContentAsString())
        .isEqualTo("{\"status\":\"FROZEN\"}");

    List<Map<String, Object>> audit =
        auditRows(walletId.toString()).stream()
            .filter(r -> "WALLET_FREEZE".equals(r.get("action")))
            .toList();
    assertThat(audit).hasSize(1);
    assertThat(audit.getFirst())
        .containsEntry("actor_id", customer.id())
        .containsEntry("actor_type", "CUSTOMER")
        .containsEntry("resource", "WALLET")
        .containsEntry("result", "SUCCESS")
        .containsEntry("correlation_id", correlationId);
    assertThat((String) audit.getFirst().get("old_value")).contains("\"status\": \"ACTIVE\"");
    assertThat((String) audit.getFirst().get("new_value"))
        .contains("\"status\": \"FROZEN\"", "\"ledgerAccountStatus\": \"FROZEN\"")
        .doesNotContain((String) before.get("wallet_number"), "reason");
  }

  @Test
  void aRepeatedFreezeSucceedsAndChangesAndAuditsNothing() throws Exception {
    Account customer = withWallet();
    postAs(FREEZE, null, customer.token());
    Map<String, Object> frozen = walletOf(customer.id());

    MockHttpServletResponse again = postAs(FREEZE, null, customer.token());

    assertThat(again.getStatus()).isEqualTo(200);
    assertThat(again.getContentAsString()).isEqualTo("{\"status\":\"FROZEN\"}");
    assertThat(walletOf(customer.id())).isEqualTo(frozen);
    assertThat(
            auditRows(frozen.get("id").toString()).stream()
                .filter(r -> "WALLET_FREEZE".equals(r.get("action"))))
        .hasSize(1);
  }

  /** Doc 08 §4B.2 requirement 6: only authorised staff unfreeze. */
  @Test
  void aCustomerCannotUnfreezeTheirWallet() throws Exception {
    Account customer = withWallet();
    postAs(FREEZE, null, customer.token());

    MockHttpServletResponse own = postAs(WALLET + "/unfreeze", null, customer.token());
    assertThat(own.getStatus()).isEqualTo(404);
    MockHttpServletResponse admin =
        postAs(
            "/api/v1/admin/customers/" + customer.id() + "/unfreeze",
            Map.of("reason", "please"),
            customer.token());
    assertThat(admin.getStatus()).isEqualTo(403);
    assertThat(errorCode(admin)).isEqualTo("FORBIDDEN");

    Map<String, Object> wallet = walletOf(customer.id());
    assertThat(wallet.get("status")).isEqualTo("FROZEN");
    assertThat(ledgerStatusOf((UUID) wallet.get("id"))).isEqualTo("FROZEN");
  }

  // Access --------------------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffAreRefusedOnEveryWalletEndpoint(Role role) throws Exception {
    String token = account(role).token();

    for (String path : READS) {
      MockHttpServletResponse response = getAs(path, token);
      assertThat(response.getStatus()).as(path).isEqualTo(403);
      assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
    }
    assertThat(postAs(FREEZE, null, token).getStatus()).isEqualTo(403);
  }

  @Test
  void withoutATokenEveryWalletEndpointIsTheGeneric401() throws Exception {
    for (String path : READS) {
      assertThat(getAs(path, null).getStatus()).as(path).isEqualTo(401);
    }
    assertThat(postAs(FREEZE, null, null).getStatus()).isEqualTo(401);
  }
}
