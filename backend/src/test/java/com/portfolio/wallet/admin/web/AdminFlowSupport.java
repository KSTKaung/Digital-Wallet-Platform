package com.portfolio.wallet.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.portfolio.securityfixture.UrlRuleProbeController;
import com.portfolio.wallet.auth.application.AccessTokenIssuer;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.ledger.application.LedgerAccountService;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.notification.application.OutboxDispatcher;
import com.portfolio.wallet.notification.application.RecordingEmailSender;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.wallet.application.WalletNumberGenerator;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared by the staff KYC flow tests (PR 4a step 3): real HTTP through MockMvc and the full
 * security chain, a recording mail sender, and the URL-rule probes. Accounts are made in the
 * database with a live session and a token from the application's issuer, as in {@code
 * UserFlowSupport}; the customer's side of KYC goes through its real endpoints. Every test uses
 * fresh accounts.
 */
@AutoConfigureMockMvc
@Import({AdminFlowSupport.Beans.class, UrlRuleProbeController.class})
abstract class AdminFlowSupport extends DatabaseIntegrationTest {

  static final String REVIEW = "/api/v1/admin/kyc/applications";
  static final String PROFILE = "/api/v1/customers/me";
  static final String APPLICATIONS = "/api/v1/kyc/applications";
  static final String STATUS = "/api/v1/kyc/status";
  static final String REFERENCE = "AB1234567";
  static final String FIRST_NAME = "Somchai";
  static final String LAST_NAME = "Jaidee";
  private static final Duration MAIL_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration NO_MAIL_WINDOW = Duration.ofMillis(300);

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
  @Autowired RecordingEmailSender mail;
  @Autowired OutboxDispatcher outboxDispatcher;

  // Declared here rather than in the failure test, so every admin flow test shares one context
  // (each context holds its own connection pool). Unstubbed, a spy calls the real bean, and spies
  // are reset after every test.
  @MockitoSpyBean LedgerAccountService ledgerAccounts;
  @MockitoSpyBean WalletNumberGenerator numbers;

  /**
   * The spy sits behind the bean's transactional proxy, so stubbing through the proxy would run the
   * transaction interceptor (and its MANDATORY check); stub and verify the spy itself.
   */
  static <T> T spied(T bean) {
    return AopTestUtils.getUltimateTargetObject(bean);
  }

  record Account(UUID id, String email, String token) {}

  Account account(Role role) throws Exception {
    UUID id = UUID.randomUUID();
    String email = "user-" + id + "@example.test";
    String phone = role == Role.CUSTOMER ? "+" + randomDigits(15) : null;
    insert(
        "auth.users",
        row(
            "id",
            id,
            "email",
            email,
            "phone_number",
            phone,
            "status",
            "ACTIVE",
            "role",
            role.name()));
    UUID session = UUID.randomUUID();
    Instant now = clock.instant();
    insert(
        "auth.sessions",
        row("id", session, "user_id", id, "last_seen_at", now.atOffset(ZoneOffset.UTC)));
    String token = issuer.issue(id, role, session, now, now.plus(Duration.ofMinutes(15)));
    return new Account(id, email, token);
  }

  Account customer() throws Exception {
    return account(Role.CUSTOMER);
  }

  Account officer() throws Exception {
    return account(Role.COMPLIANCE_OFFICER);
  }

  static Map<String, Object> kycBody(String level) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("verificationLevel", level);
    body.put("documentType", "ID_CARD");
    body.put("documentReference", REFERENCE);
    body.put("firstName", FIRST_NAME);
    body.put("lastName", LAST_NAME);
    return body;
  }

  /** A customer's draft, through the customer endpoints; returns its id. */
  UUID draft(Account customer, String level) throws Exception {
    MockHttpServletResponse response = postAs(APPLICATIONS, kycBody(level), customer.token());
    assertThat(response.getStatus()).as("create").isEqualTo(201);
    return UUID.fromString(body(response).get("id").asString());
  }

  /** A customer's application in {@code PENDING_REVIEW}, with a complete profile. */
  UUID pending(Account customer, String level) throws Exception {
    MockHttpServletResponse profile =
        putAs(PROFILE, Map.of("dateOfBirth", "1990-05-17", "countryCode", "TH"), customer.token());
    assertThat(profile.getStatus()).as("profile").isEqualTo(200);
    UUID id = draft(customer, level);
    MockHttpServletResponse submitted =
        postAs(APPLICATIONS + "/" + id + "/submit", null, customer.token());
    assertThat(submitted.getStatus()).as("submit").isEqualTo(200);
    return id;
  }

  UUID pending(Account customer) throws Exception {
    return pending(customer, "BASIC");
  }

  MockHttpServletResponse approve(UUID application, String token) throws Exception {
    return postAs(REVIEW + "/" + application + "/approve", null, token);
  }

  MockHttpServletResponse reject(UUID application, String reason, String token) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    return postAs(REVIEW + "/" + application + "/reject", body, token);
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

  MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonMapper.writeValueAsString(body));
  }

  MockHttpServletResponse getAs(String path, String token) throws Exception {
    return send(get(path), token);
  }

  MockHttpServletResponse postAs(String path, Object body, String token) throws Exception {
    return send(body == null ? post(path) : json(post(path), body), token);
  }

  MockHttpServletResponse putAs(String path, Object body, String token) throws Exception {
    return send(json(put(path), body), token);
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

  // Staff list ----------------------------------------------------------------------------------
  // The queue is oldest first (P2) and shared by every test, so a test's own rows are at its end.

  private String listPage(String query, int page) {
    return REVIEW + "?" + (query.isEmpty() ? "" : query + "&") + "size=100&page=" + page;
  }

  /** The last two pages of the staff list for {@code query}, where the newest submissions are. */
  List<MockHttpServletResponse> lastListPages(String query, String token) throws Exception {
    JsonNode first = body(getAs(listPage(query, 0), token));
    int last = (int) Math.max(0, first.get("page").get("totalPages").asLong() - 1);
    List<MockHttpServletResponse> pages = new java.util.ArrayList<>();
    if (last > 0) {
      pages.add(getAs(listPage(query, last - 1), token));
    }
    pages.add(getAs(listPage(query, last), token));
    return pages;
  }

  /** Every item of the staff list for {@code query}, in list order, page by page. */
  List<JsonNode> allListed(String query, String token) throws Exception {
    List<JsonNode> items = new java.util.ArrayList<>();
    for (int page = 0; ; page++) {
      JsonNode body = body(getAs(listPage(query, page), token));
      body.get("items").forEach(items::add);
      if (page + 1 >= body.get("page").get("totalPages").asLong()) {
        return items;
      }
    }
  }

  List<String> allListedIds(String query, String token) throws Exception {
    return allListed(query, token).stream().map(item -> item.get("id").asString()).toList();
  }

  // Database reads ----------------------------------------------------------------------------

  List<Map<String, Object>> walletsOf(UUID customerId) {
    return jdbc.queryForList(
        "SELECT id, wallet_number, tier, status, currency, available_balance, reserved_balance,"
            + " version FROM wallet.wallets WHERE customer_id = ?",
        customerId);
  }

  List<Map<String, Object>> ledgerAccountsOf(UUID walletId) {
    return jdbc.queryForList(
        "SELECT id, code, account_type, owner_reference, currency, status"
            + " FROM ledger.ledger_accounts WHERE owner_reference = ?",
        walletId);
  }

  Map<String, Object> application(UUID id) {
    return jdbc.queryForMap(
        "SELECT status, reviewer_id, rejection_reason, decided_at FROM usr.kyc_applications"
            + " WHERE id = ?",
        id);
  }

  Map<String, Object> profile(UUID customerId) {
    return jdbc.queryForMap(
        "SELECT first_name, last_name FROM usr.customers WHERE user_id = ?", customerId);
  }

  List<Map<String, Object>> auditRows(String resourceId) {
    return jdbc.queryForList(
        "SELECT actor_id, actor_type, action, resource, result, correlation_id,"
            + " old_value::text AS old_value, new_value::text AS new_value"
            + " FROM audit.audit_logs WHERE resource_id = ? ORDER BY id",
        resourceId);
  }

  // Mail --------------------------------------------------------------------------------------
  // Business emails go through the outbox (MVP-ADR-25); the scheduled job is off in tests, so each
  // check runs the dispatcher first, as the job would.

  /** Sends every due outbox row now, as one run of the scheduled job would. */
  void deliverOutbox() {
    while (outboxDispatcher.dispatchDue() > 0) {
      // until no row is due
    }
  }

  List<EmailMessage> mailTo(String address, EmailKind kind) {
    deliverOutbox();
    return mail.messages().stream()
        .filter(m -> m.to().equals(address) && m.kind() == kind)
        .toList();
  }

  EmailMessage awaitMail(String address, EmailKind kind) {
    await().atMost(MAIL_TIMEOUT).until(() -> !mailTo(address, kind).isEmpty());
    return mailTo(address, kind).getFirst();
  }

  /** Absence is checked over a short window, with the dispatcher run on every check. */
  void assertNoMailTo(String address) {
    await()
        .during(NO_MAIL_WINDOW)
        .atMost(MAIL_TIMEOUT)
        .until(
            () -> {
              deliverOutbox();
              return mail.messages().stream().noneMatch(m -> m.to().equals(address));
            });
  }

  /**
   * Over the same short window, {@code address} holds exactly {@code count} mails of {@code kind}.
   */
  void assertMailCountStays(String address, EmailKind kind, int count) {
    await().atMost(MAIL_TIMEOUT).until(() -> mailTo(address, kind).size() >= count);
    await()
        .during(NO_MAIL_WINDOW)
        .atMost(MAIL_TIMEOUT)
        .until(() -> mailTo(address, kind).size() == count);
  }

  /** Over the short window, no mail of {@code kind} reaches anyone. */
  void assertNoNewMailOfKind(EmailKind kind, long before) {
    await().during(NO_MAIL_WINDOW).atMost(MAIL_TIMEOUT).until(() -> mailCount(kind) == before);
  }

  long mailCount(EmailKind kind) {
    deliverOutbox();
    return mail.messages().stream().filter(m -> m.kind() == kind).count();
  }

  /** The outbox rows written for {@code userId}, oldest first. */
  List<Map<String, Object>> outboxRows(UUID userId) {
    return jdbc.queryForList(
        "SELECT event_id, kind, status, attempts, payload::text AS payload, last_failure,"
            + " occurred_at, next_attempt_at FROM notification.notifications"
            + " WHERE user_id = ? ORDER BY occurred_at, event_id",
        userId);
  }
}
