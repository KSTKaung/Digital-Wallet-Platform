package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.portfolio.wallet.auth.application.AccessTokenIssuer;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.notification.application.RecordingEmailSender;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared by the customer wallet flow tests (PR 4a step 4): real HTTP through MockMvc and the full
 * security chain. Accounts are made in the database with a live session, as in {@code
 * UserFlowSupport}; the wallet comes from the real path, profile, KYC submit and a compliance
 * officer's approval, so these tests also prove that approval and the wallet endpoints agree. Mail
 * goes to a recorder. Every test uses fresh accounts.
 */
@AutoConfigureMockMvc
@Import(WalletFlowSupport.Beans.class)
abstract class WalletFlowSupport extends DatabaseIntegrationTest {

  static final String WALLET = "/api/v1/wallet";
  static final String BALANCE = WALLET + "/balance";
  static final String STATUS = WALLET + "/status";
  static final String LIMITS = WALLET + "/limits";
  static final String FREEZE = WALLET + "/freeze";

  @TestConfiguration
  static class Beans {
    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
      return new RecordingEmailSender();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired AccessTokenIssuer issuer;
  @Autowired Clock clock;

  record Account(UUID id, String token) {}

  Account account(Role role) throws Exception {
    UUID id = UUID.randomUUID();
    insert(
        "auth.users",
        row(
            "id",
            id,
            "email",
            "user-" + id + "@example.test",
            "phone_number",
            role == Role.CUSTOMER ? "+" + randomDigits(15) : null,
            "status",
            "ACTIVE",
            "role",
            role.name()));
    UUID session = UUID.randomUUID();
    Instant now = clock.instant();
    insert(
        "auth.sessions",
        row("id", session, "user_id", id, "last_seen_at", now.atOffset(ZoneOffset.UTC)));
    return new Account(id, issuer.issue(id, role, session, now, now.plus(Duration.ofMinutes(15))));
  }

  Account customer() throws Exception {
    return account(Role.CUSTOMER);
  }

  /** A customer whose KYC {@code level} application is submitted but not decided. */
  UUID submitted(Account customer, String level) throws Exception {
    assertThat(
            putAs(
                    "/api/v1/customers/me",
                    Map.of("dateOfBirth", "1990-05-17", "countryCode", "TH"),
                    customer.token())
                .getStatus())
        .as("profile")
        .isEqualTo(200);
    Map<String, Object> kyc = new LinkedHashMap<>();
    kyc.put("verificationLevel", level);
    kyc.put("documentType", "ID_CARD");
    kyc.put("documentReference", "AB1234567");
    kyc.put("firstName", "Somchai");
    kyc.put("lastName", "Jaidee");
    MockHttpServletResponse created = postAs("/api/v1/kyc/applications", kyc, customer.token());
    assertThat(created.getStatus()).as("create").isEqualTo(201);
    UUID application = UUID.fromString(body(created).get("id").asString());
    assertThat(
            postAs("/api/v1/kyc/applications/" + application + "/submit", null, customer.token())
                .getStatus())
        .as("submit")
        .isEqualTo(200);
    return application;
  }

  /** A customer with an approved {@code level} application, and so a wallet. */
  Account withWallet(String level) throws Exception {
    Account customer = customer();
    UUID application = submitted(customer, level);
    String officer = account(Role.COMPLIANCE_OFFICER).token();
    assertThat(
            postAs("/api/v1/admin/kyc/applications/" + application + "/approve", null, officer)
                .getStatus())
        .as("approve")
        .isEqualTo(200);
    return customer;
  }

  Account withWallet() throws Exception {
    return withWallet("BASIC");
  }

  MockHttpServletResponse send(MockHttpServletRequestBuilder request, String token)
      throws Exception {
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletResponse sendWithCorrelation(
      MockHttpServletRequestBuilder request, String token, String correlationId) throws Exception {
    return send(request.header(CorrelationId.HEADER, correlationId), token);
  }

  MockHttpServletResponse getAs(String path, String token) throws Exception {
    return send(get(path), token);
  }

  MockHttpServletResponse postAs(String path, Object body, String token) throws Exception {
    return send(
        body == null
            ? post(path)
            : post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)),
        token);
  }

  MockHttpServletResponse putAs(String path, Object body, String token) throws Exception {
    return send(
        put(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(body)),
        token);
  }

  JsonNode body(MockHttpServletResponse response) throws Exception {
    return jsonMapper.readTree(response.getContentAsString());
  }

  String errorCode(MockHttpServletResponse response) throws Exception {
    return body(response).get("errorCode").asString();
  }

  /** The body without the fields that differ on every response. */
  static String stable(MockHttpServletResponse response) throws Exception {
    return response
        .getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "")
        .replaceAll("\"correlationId\":\"[^\"]*\"", "");
  }

  // Database reads ----------------------------------------------------------------------------

  Map<String, Object> walletOf(UUID customerId) {
    return jdbc.queryForMap(
        "SELECT id, wallet_number, status, version FROM wallet.wallets WHERE customer_id = ?",
        customerId);
  }

  String ledgerStatusOf(UUID walletId) {
    return jdbc.queryForObject(
        "SELECT status FROM ledger.ledger_accounts WHERE owner_reference = ?"
            + " AND account_type = 'CUSTOMER'",
        String.class,
        walletId);
  }

  List<Map<String, Object>> auditRows(String resourceId) {
    return jdbc.queryForList(
        "SELECT actor_id, actor_type, action, resource, result, correlation_id,"
            + " old_value::text AS old_value, new_value::text AS new_value"
            + " FROM audit.audit_logs WHERE resource_id = ? ORDER BY id",
        resourceId);
  }
}
