package com.portfolio.wallet.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.auth.domain.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Staff freeze and unfreeze (documentations/02 §2.7; doc 06 MVP-ADR-10 amendment; doc 08 §4B.2 Q9,
 * §4B.4 AC 9): {@code ADMIN} only, with a reason that is audited; the wallet and its ledger account
 * always change together; an unknown customer and one without a wallet give the identical 404.
 */
class WalletFreezeFlowIT extends AdminFlowSupport {

  private static final String CUSTOMERS = "/api/v1/admin/customers/";

  private String admin() throws Exception {
    return account(Role.ADMIN).token();
  }

  /** A customer with an approved application, and so an ACTIVE wallet; returns the wallet id. */
  private UUID walletFor(Account customer) throws Exception {
    assertThat(approve(pending(customer), officer().token()).getStatus()).isEqualTo(200);
    return (UUID) walletsOf(customer.id()).getFirst().get("id");
  }

  private MockHttpServletResponse change(String action, UUID customer, String reason, String token)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    return postAs(CUSTOMERS + customer + "/" + action, body, token);
  }

  private String walletStatus(UUID walletId) {
    return jdbc.queryForObject(
        "SELECT status FROM wallet.wallets WHERE id = ?", String.class, walletId);
  }

  private String ledgerStatus(UUID walletId) {
    return (String) ledgerAccountsOf(walletId).getFirst().get("status");
  }

  private void assertStatuses(UUID walletId, String status) {
    assertThat(walletStatus(walletId)).isEqualTo(status);
    assertThat(ledgerStatus(walletId)).as("ledger account follows the wallet").isEqualTo(status);
  }

  private List<Map<String, Object>> rows(UUID walletId, String action) {
    return auditRows(walletId.toString()).stream()
        .filter(r -> action.equals(r.get("action")))
        .toList();
  }

  @Test
  void anAdminFreezesAndUnfreezesWithAnAuditedReasonAndTheStatusesStayInStep() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    Account admin = account(Role.ADMIN);

    MockHttpServletResponse frozen =
        change("freeze", customer.id(), "Reported stolen phone", admin.token());

    assertThat(frozen.getStatus()).isEqualTo(200);
    assertThat(frozen.getContentAsString()).isEqualTo("{\"status\":\"FROZEN\"}");
    assertStatuses(walletId, "FROZEN");
    List<Map<String, Object>> freezes = rows(walletId, "WALLET_FREEZE");
    assertThat(freezes).hasSize(1);
    assertThat(freezes.getFirst())
        .containsEntry("actor_id", admin.id())
        .containsEntry("actor_type", "STAFF")
        .containsEntry("result", "SUCCESS");
    assertThat((String) freezes.getFirst().get("new_value"))
        .contains(
            "\"reason\": \"Reported stolen phone\"",
            "\"customerId\": \"" + customer.id() + "\"",
            "\"ledgerAccountStatus\": \"FROZEN\"");

    MockHttpServletResponse active =
        change("unfreeze", customer.id(), "Customer confirmed by phone", admin.token());

    assertThat(active.getStatus()).isEqualTo(200);
    assertThat(active.getContentAsString()).isEqualTo("{\"status\":\"ACTIVE\"}");
    assertStatuses(walletId, "ACTIVE");
    List<Map<String, Object>> unfreezes = rows(walletId, "WALLET_UNFREEZE");
    assertThat(unfreezes).hasSize(1);
    assertThat((String) unfreezes.getFirst().get("old_value")).contains("FROZEN");
    assertThat((String) unfreezes.getFirst().get("new_value"))
        .contains("\"reason\": \"Customer confirmed by phone\"");
    // The response holds no balance, wallet number or id.
    assertThat(frozen.getContentAsString() + active.getContentAsString())
        .doesNotContain(walletId.toString(), "Balance", "walletNumber");
  }

  /** A customer's own freeze is undone only by staff. */
  @Test
  void aCustomersOwnFreezeIsUndoneByAnAdmin() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    assertThat(postAs("/api/v1/wallet/freeze", null, customer.token()).getStatus()).isEqualTo(200);
    assertStatuses(walletId, "FROZEN");

    assertThat(change("unfreeze", customer.id(), "Customer request", admin()).getStatus())
        .isEqualTo(200);

    assertStatuses(walletId, "ACTIVE");
  }

  @Test
  void repeatingAFreezeOrAnUnfreezeIsASuccessThatChangesAndAuditsNothing() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    String admin = admin();

    MockHttpServletResponse unfreezeActive = change("unfreeze", customer.id(), "no-op", admin);
    assertThat(unfreezeActive.getStatus()).isEqualTo(200);
    assertThat(unfreezeActive.getContentAsString()).isEqualTo("{\"status\":\"ACTIVE\"}");
    change("freeze", customer.id(), "first", admin);
    Long version =
        jdbc.queryForObject(
            "SELECT version FROM wallet.wallets WHERE id = ?", Long.class, walletId);

    MockHttpServletResponse again = change("freeze", customer.id(), "second", admin);

    assertThat(again.getStatus()).isEqualTo(200);
    assertThat(again.getContentAsString()).isEqualTo("{\"status\":\"FROZEN\"}");
    assertStatuses(walletId, "FROZEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM wallet.wallets WHERE id = ?", Long.class, walletId))
        .isEqualTo(version);
    assertThat(rows(walletId, "WALLET_FREEZE")).hasSize(1);
    assertThat(rows(walletId, "WALLET_UNFREEZE")).isEmpty();
  }

  @Test
  void anUnknownCustomerAndOneWithoutAWalletGiveTheIdentical404() throws Exception {
    String admin = admin();
    Account pendingCustomer = customer();
    pending(pendingCustomer);
    Account staff = officer();

    MockHttpServletResponse unknown = change("freeze", UUID.randomUUID(), "reason", admin);
    MockHttpServletResponse noWallet = change("freeze", pendingCustomer.id(), "reason", admin);
    MockHttpServletResponse staffId = change("unfreeze", staff.id(), "reason", admin);

    for (MockHttpServletResponse response : List.of(unknown, noWallet, staffId)) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(errorCode(response)).isEqualTo("NOT_FOUND");
    }
    assertThat(stable(noWallet)).isEqualTo(stable(unknown)).isEqualTo(stable(staffId));
  }

  @Test
  void aComplianceOfficerAndACustomerAreRefusedAndNothingChanges() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);

    for (String token : List.of(officer().token(), customer.token())) {
      for (String action : List.of("freeze", "unfreeze")) {
        MockHttpServletResponse response = change(action, customer.id(), "reason", token);
        assertThat(response.getStatus()).as(action).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
      }
    }
    assertStatuses(walletId, "ACTIVE");
    assertThat(rows(walletId, "WALLET_FREEZE")).isEmpty();
  }

  /** Refused before the body is read: a malformed body is still a 403 for the wrong role. */
  @Test
  void aComplianceOfficerWithAMalformedBodyIsRefusedBeforeTheBodyIsRead() throws Exception {
    MockHttpServletResponse response =
        send(
            post(CUSTOMERS + UUID.randomUUID() + "/freeze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": "),
            officer().token());

    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void withoutATokenItIsTheGeneric401() throws Exception {
    MockHttpServletResponse response = change("freeze", UUID.randomUUID(), "reason", null);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(errorCode(response)).isEqualTo("UNAUTHORIZED");
  }

  @ParameterizedTest(name = "[{index}]")
  @ValueSource(strings = {"", "   ", "line\nbreak", "nul\u0000"})
  void aBlankOrControlCharacterReasonIsAValidationErrorNamingTheField(String reason)
      throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);

    MockHttpServletResponse response = change("freeze", customer.id(), reason, admin());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
    assertThat(body(response).get("message").asString()).startsWith("reason: ");
    assertStatuses(walletId, "ACTIVE");
  }

  @Test
  void aReasonIsRequiredAndAtMost500Characters() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    String admin = admin();

    MockHttpServletResponse missing =
        postAs(CUSTOMERS + customer.id() + "/freeze", Map.of(), admin);
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat(body(missing).get("message").asString()).startsWith("reason: ");
    MockHttpServletResponse noBody = postAs(CUSTOMERS + customer.id() + "/freeze", null, admin);
    assertThat(noBody.getStatus()).isEqualTo(400);
    MockHttpServletResponse tooLong = change("freeze", customer.id(), "x".repeat(501), admin);
    assertThat(tooLong.getStatus()).isEqualTo(400);
    assertThat(body(tooLong).get("message").asString())
        .startsWith("reason: ")
        .doesNotContain("xxx");
    assertStatuses(walletId, "ACTIVE");

    assertThat(change("freeze", customer.id(), "x".repeat(500), admin).getStatus()).isEqualTo(200);
    assertStatuses(walletId, "FROZEN");
  }

  @Test
  void aMalformedCustomerIdIsAValidationError() throws Exception {
    MockHttpServletResponse response =
        postAs(CUSTOMERS + "not-a-uuid/freeze", Map.of("reason", "reason"), admin());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
  }
}
