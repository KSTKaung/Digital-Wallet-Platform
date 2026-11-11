package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.portfolio.wallet.audit.persistence.AuditLogRepository;
import com.portfolio.wallet.auth.application.AccessTokenIssuer;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.ledger.persistence.LedgerPostingRepository;
import com.portfolio.wallet.notification.application.RecordingEmailSender;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.wallet.application.WalletNumberGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared by the transfer flow tests (PR 5a step 5): real HTTP through MockMvc and the full security
 * chain, accounts with live sessions, wallets from the real KYC flow, funding by real deposits, and
 * spies on the repositories of the money transaction so a failure can be injected after any step.
 *
 * <p>The risk policy is the production one ({@code application.yaml}): a sender wallet younger than
 * 24 hours (+20) sending to a new recipient (+15) scores 35, so transfers up to 10,000.00 THB are
 * {@code ALLOW} and larger ones {@code MONITOR}; {@link TransferReviewFlowSupport} uses a test
 * policy whose amounts choose each band.
 *
 * <p>Every transfer request gets its own random client IP unless the test passes one, because the
 * per-IP lookup window (30 per minute, Q5) is shared by everything the test JVM sends from one
 * address. Each test uses fresh accounts.
 */
@AutoConfigureMockMvc
@Import(TransferFlowSupport.Beans.class)
abstract class TransferFlowSupport extends DatabaseIntegrationTest {

  static final String TRANSFERS = "/api/v1/transfers";
  static final String KEY_HEADER = "Idempotency-Key";

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

  @MockitoSpyBean TransferRepository transferRowsSpy;
  @MockitoSpyBean LedgerPostingRepository ledgerRowsSpy;
  @MockitoSpyBean AuditLogRepository auditRowsSpy;
  @MockitoSpyBean RiskAssessmentRepository riskRowsSpy;

  record Account(UUID id, String token) {}

  // Accounts ------------------------------------------------------------------------------------

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

  /** A customer with an approved {@code level} application, and so a wallet. */
  Account withWallet(String level) throws Exception {
    Account customer = customer();
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

  /** A customer with a wallet holding {@code amount} THB, by deposits of at most 100,000.00. */
  Account funded(String amount) throws Exception {
    Account customer = withWallet();
    fund(customer, amount);
    return customer;
  }

  void fund(Account customer, String amount) throws Exception {
    BigDecimal left = new BigDecimal(amount);
    BigDecimal max = new BigDecimal("100000.00");
    while (left.signum() > 0) {
      BigDecimal part = left.min(max);
      MockHttpServletResponse deposit =
          send(
              post("/api/v1/wallet/deposits")
                  .contentType(MediaType.APPLICATION_JSON)
                  .header(KEY_HEADER, UUID.randomUUID().toString())
                  .content("{\"amount\":" + part.toPlainString() + ",\"currency\":\"THB\"}"),
              customer.token());
      assertThat(deposit.getStatus()).as("deposit " + part).isEqualTo(201);
      left = left.subtract(part);
    }
  }

  MockHttpServletResponse freeze(Account customer) throws Exception {
    return postAs("/api/v1/wallet/freeze", null, customer.token());
  }

  // Requests ------------------------------------------------------------------------------------

  static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  static String transferBody(String walletNumber, String amount, String description) {
    StringBuilder json =
        new StringBuilder("{\"receiverWalletNumber\":\"")
            .append(walletNumber)
            .append("\",\"amount\":")
            .append(amount)
            .append(",\"currency\":\"THB\"");
    if (description != null) {
      json.append(",\"description\":\"").append(description).append('"');
    }
    return json.append('}').toString();
  }

  MockHttpServletResponse postTransfer(String json, String token, UUID key, String ip)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post(TRANSFERS)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .with(
                r -> {
                  r.setRemoteAddr(ip);
                  return r;
                });
    if (key != null) {
      request.header(KEY_HEADER, key.toString());
    }
    return send(request, token);
  }

  MockHttpServletResponse transfer(Account sender, Account recipient, String amount, UUID key)
      throws Exception {
    return postTransfer(
        transferBody(numberOf(recipient), amount, "rent"), sender.token(), key, randomIp());
  }

  MockHttpServletResponse transfer(Account sender, Account recipient, String amount)
      throws Exception {
    return transfer(sender, recipient, amount, UUID.randomUUID());
  }

  MockHttpServletResponse cancel(UUID transferId, Account caller) throws Exception {
    return send(post(TRANSFERS + "/" + transferId + "/cancel"), caller.token());
  }

  MockHttpServletResponse send(MockHttpServletRequestBuilder request, String token)
      throws Exception {
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return mvc.perform(request).andReturn().getResponse();
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

  UUID transferId(MockHttpServletResponse response) throws Exception {
    return UUID.fromString(body(response).get("id").asString());
  }

  /** The body without the fields that differ on every response. */
  static String stable(MockHttpServletResponse response) throws Exception {
    return response
        .getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "")
        .replaceAll("\"correlationId\":\"[^\"]*\"", "");
  }

  static String absentNumber() {
    return new WalletNumberGenerator().next().value();
  }

  /** The number with its check digit changed, so it can never be a wallet's (X2). */
  static String wrongCheckDigit(String number) {
    char last = number.charAt(number.length() - 1);
    char other = (char) ('0' + ((last - '0' + 1) % 10));
    return number.substring(0, number.length() - 1) + other;
  }

  // Database reads ----------------------------------------------------------------------------

  String numberOf(Account account) {
    return jdbc.queryForObject(
        "SELECT wallet_number FROM wallet.wallets WHERE customer_id = ?",
        String.class,
        account.id());
  }

  UUID walletId(Account account) {
    return jdbc.queryForObject(
        "SELECT id FROM wallet.wallets WHERE customer_id = ?", UUID.class, account.id());
  }

  BigDecimal available(Account account) {
    return jdbc.queryForObject(
        "SELECT available_balance FROM wallet.wallets WHERE customer_id = ?",
        BigDecimal.class,
        account.id());
  }

  BigDecimal reserved(Account account) {
    return jdbc.queryForObject(
        "SELECT reserved_balance FROM wallet.wallets WHERE customer_id = ?",
        BigDecimal.class,
        account.id());
  }

  void assertBalances(Account account, String available, String reserved) {
    assertThat(available(account)).as("available").isEqualByComparingTo(available);
    assertThat(reserved(account)).as("reserved").isEqualByComparingTo(reserved);
    assertTotalMatchesLedger(account);
  }

  /** Σ CREDIT − Σ DEBIT on the wallet's customer account (doc 01 §6.2). */
  BigDecimal ledgerBalance(Account account) {
    return jdbc.queryForObject(
        """
        SELECT coalesce(sum(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
        FROM ledger.ledger_entries e JOIN ledger.ledger_accounts a ON a.id = e.account_id
        WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER'
        """,
        BigDecimal.class,
        walletId(account));
  }

  /** MVP-ADR-03 check 1: available + reserved = ledger balance. */
  void assertTotalMatchesLedger(Account account) {
    assertThat(available(account).add(reserved(account)))
        .as("available + reserved = ledger")
        .isEqualByComparingTo(ledgerBalance(account));
  }

  /** used and reserved of the wallet's TRANSFER usage on {@code date}; empty if no row. */
  List<Map<String, Object>> transferUsage(Account account, LocalDate date) {
    return jdbc.queryForList(
        "SELECT used, reserved FROM wallet.daily_limit_usage"
            + " WHERE wallet_id = ? AND operation = 'TRANSFER' AND limit_date = ?",
        walletId(account),
        date);
  }

  BigDecimal transferUsed(Account account) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(used), 0) FROM wallet.daily_limit_usage"
            + " WHERE wallet_id = ? AND operation = 'TRANSFER'",
        BigDecimal.class,
        walletId(account));
  }

  BigDecimal transferReserved(Account account) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(reserved), 0) FROM wallet.daily_limit_usage"
            + " WHERE wallet_id = ? AND operation = 'TRANSFER'",
        BigDecimal.class,
        walletId(account));
  }

  Map<String, Object> transferRow(UUID id) {
    return jdbc.queryForMap("SELECT * FROM transfer.transfers WHERE id = ?", id);
  }

  List<Map<String, Object>> transfersOf(Account sender) {
    return jdbc.queryForList(
        "SELECT * FROM transfer.transfers WHERE sender_customer_id = ? ORDER BY created_at",
        sender.id());
  }

  int transferRecords(Account sender, UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM transfer.idempotency_records"
            + " WHERE customer_id = ? AND idempotency_key = ?",
        Integer.class,
        sender.id(),
        key);
  }

  int assessments(Account sender) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM risk.risk_assessments WHERE customer_id = ?",
        Integer.class,
        sender.id());
  }

  String decisionOf(UUID transferId) {
    return jdbc.queryForObject(
        "SELECT decision FROM risk.risk_assessments WHERE transfer_id = ?",
        String.class,
        transferId);
  }

  /** Ledger transactions with this reference, and their entries' count. */
  int ledgerTransactions(String reference) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ledger.financial_transactions WHERE transaction_reference = ?",
        Integer.class,
        reference);
  }

  /** Σ DEBIT = Σ CREDIT for the transaction with this reference, and how many entries it has. */
  void assertBalancedPosting(String reference, String amount) {
    Map<String, Object> sums =
        jdbc.queryForMap(
            """
            SELECT t.transaction_type AS type, count(e.id) AS entries,
                   sum(CASE WHEN e.entry_type = 'DEBIT' THEN e.amount ELSE 0 END) AS debits,
                   sum(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE 0 END) AS credits
            FROM ledger.financial_transactions t
            JOIN ledger.ledger_entries e ON e.transaction_id = t.id
            WHERE t.transaction_reference = ?
            GROUP BY t.transaction_type
            """,
            reference);
    assertThat(sums.get("type")).isEqualTo("TRANSFER");
    assertThat(((Number) sums.get("entries")).intValue()).isEqualTo(2);
    assertThat((BigDecimal) sums.get("debits")).isEqualByComparingTo(amount);
    assertThat((BigDecimal) sums.get("credits")).isEqualByComparingTo(amount);
  }

  List<Map<String, Object>> transferAudits(Account actor) {
    return jdbc.queryForList(
        "SELECT action, resource, resource_id, result, actor_type, host(client_ip) AS ip,"
            + " old_value::text AS old_value, new_value::text AS new_value"
            + " FROM audit.audit_logs WHERE actor_id = ?"
            + " AND action IN ('TRANSFER', 'TRANSFER_REVIEW') ORDER BY id",
        actor.id());
  }

  List<Map<String, Object>> lookupAudits(Account actor) {
    return jdbc.queryForList(
        "SELECT result, resource_id, host(client_ip) AS ip, new_value::text AS new_value"
            + " FROM audit.audit_logs WHERE actor_id = ? AND action = 'WALLET_LOOKUP' ORDER BY id",
        actor.id());
  }

  int lookupAttempts(Account actor) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM wallet.lookup_attempts WHERE user_id = ?", Integer.class, actor.id());
  }

  List<Map<String, Object>> reviewRows(UUID transferId) {
    return jdbc.queryForList(
        "SELECT action, decided_by, requested_action, actor_user_id, system_reason,"
            + " reason_category, reason FROM risk.risk_reviews WHERE transfer_id = ?",
        transferId);
  }
}
