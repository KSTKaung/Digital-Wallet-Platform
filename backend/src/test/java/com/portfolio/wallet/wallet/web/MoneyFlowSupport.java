package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.audit.persistence.AuditLogRepository;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.ledger.persistence.LedgerPostingRepository;
import com.portfolio.wallet.support.MutableClock;
import com.portfolio.wallet.wallet.application.MoneyMovementService;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import com.portfolio.wallet.wallet.persistence.MoneyMovementRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared by the deposit and withdrawal tests (PR 4b step 8): the wallet flow of {@link
 * WalletFlowSupport} with a {@link MutableClock}, so business-day boundaries can be crossed, and
 * spies on the steps of the money transaction, so a failure can be injected after any of them. All
 * these tests share one Spring context.
 */
@Import({WalletFlowSupport.Beans.class, MoneyFlowSupport.Clocks.class})
abstract class MoneyFlowSupport extends WalletFlowSupport {

  static final String DEPOSITS = WALLET + "/deposits";
  static final String WITHDRAWALS = WALLET + "/withdrawals";
  static final String KEY_HEADER = "Idempotency-Key";

  @TestConfiguration
  static class Clocks {
    @Bean
    @Primary
    MutableClock moneyTestClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock mutableClock;
  @Autowired MoneyMovementService movements;

  // Repositories, not the services: a transactional proxy with MANDATORY propagation cannot be
  // stubbed outside a transaction.
  @MockitoSpyBean LedgerPostingRepository ledgerRowsSpy;
  @MockitoSpyBean MoneyMovementRepository movementRowsSpy;
  @MockitoSpyBean AuditLogRepository auditRowsSpy;
  @MockitoSpyBean IdempotencyRecordRepository idempotencyRowsSpy;

  @BeforeEach
  void realTime() {
    mutableClock.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  // Requests ------------------------------------------------------------------------------------

  MockHttpServletResponse postMoney(String path, String json, String token, UUID key)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post(path).contentType(MediaType.APPLICATION_JSON).content(json);
    if (key != null) {
      request.header(KEY_HEADER, key.toString());
    }
    return send(request, token);
  }

  MockHttpServletResponse deposit(Account account, String amount, UUID key) throws Exception {
    return postMoney(
        DEPOSITS,
        "{\"amount\":" + amount + ",\"currency\":\"THB\",\"reference\":\"salary\"}",
        account.token(),
        key);
  }

  MockHttpServletResponse withdraw(Account account, String amount, UUID key) throws Exception {
    return postMoney(
        WITHDRAWALS, "{\"amount\":" + amount + ",\"currency\":\"THB\"}", account.token(), key);
  }

  /** A deposit that must succeed, with a fresh key. */
  void funded(Account account, String amount) throws Exception {
    assertThat(deposit(account, amount, UUID.randomUUID()).getStatus())
        .as("deposit " + amount)
        .isEqualTo(201);
  }

  /** A new session and token for the same customer at the clock's current time. */
  Account signedInAgain(Account account) throws Exception {
    UUID session = UUID.randomUUID();
    Instant now = mutableClock.instant();
    insert(
        "auth.sessions",
        row("id", session, "user_id", account.id(), "last_seen_at", now.atOffset(ZoneOffset.UTC)));
    return new Account(
        account.id(),
        issuer.issue(account.id(), Role.CUSTOMER, session, now, now.plus(Duration.ofMinutes(15))));
  }

  // Database reads ----------------------------------------------------------------------------

  UUID walletId(Account account) {
    return (UUID) walletOf(account.id()).get("id");
  }

  BigDecimal available(UUID walletId) {
    return jdbc.queryForObject(
        "SELECT available_balance FROM wallet.wallets WHERE id = ?", BigDecimal.class, walletId);
  }

  /** Σ CREDIT − Σ DEBIT on the wallet's customer account (doc 01 §6.2). */
  BigDecimal ledgerBalance(UUID walletId) {
    return jdbc.queryForObject(
        """
        SELECT coalesce(sum(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
        FROM ledger.ledger_entries e JOIN ledger.ledger_accounts a ON a.id = e.account_id
        WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER'
        """,
        BigDecimal.class,
        walletId);
  }

  /** Ledger transactions that touch the wallet's account. */
  int ledgerTransactions(UUID walletId) {
    return jdbc.queryForObject(
        """
        SELECT count(DISTINCT e.transaction_id)
        FROM ledger.ledger_entries e JOIN ledger.ledger_accounts a ON a.id = e.account_id
        WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER'
        """,
        Integer.class,
        walletId);
  }

  /** Transactions touching the wallet whose debits and credits differ, or with < 2 entries. */
  int unbalancedTransactions(UUID walletId) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FROM (
          SELECT t.transaction_id
          FROM ledger.ledger_entries t
          WHERE t.transaction_id IN (
            SELECT e.transaction_id FROM ledger.ledger_entries e
            JOIN ledger.ledger_accounts a ON a.id = e.account_id
            WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER')
          GROUP BY t.transaction_id
          HAVING count(*) < 2
              OR sum(CASE WHEN t.entry_type = 'DEBIT' THEN t.amount ELSE 0 END)
                 <> sum(CASE WHEN t.entry_type = 'CREDIT' THEN t.amount ELSE 0 END)) x
        """,
        Integer.class,
        walletId);
  }

  int rows(String table, UUID walletId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE wallet_id = ?", Integer.class, walletId);
  }

  int idempotencyRecords(UUID customerId, UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM wallet.idempotency_records"
            + " WHERE customer_id = ? AND idempotency_key = ?",
        Integer.class,
        customerId,
        key);
  }

  int moneyAuditRows(UUID customerId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs"
            + " WHERE actor_id = ? AND action IN ('DEPOSIT', 'WITHDRAWAL')",
        Integer.class,
        customerId);
  }

  BigDecimal usedToday(UUID walletId, String operation) {
    return jdbc.query(
        "SELECT sum(used) FROM wallet.daily_limit_usage WHERE wallet_id = ? AND operation = ?",
        rs -> rs.next() && rs.getBigDecimal(1) != null ? rs.getBigDecimal(1) : BigDecimal.ZERO,
        walletId,
        operation);
  }

  Map<String, Object> movementRow(String table, UUID id) {
    return jdbc.queryForMap("SELECT * FROM " + table + " WHERE id = ?", id);
  }

  /** Snapshot equals the ledger and every posting is balanced (MVP-ADR-03 reconciliation 1). */
  void assertReconciled(UUID walletId) {
    assertThat(available(walletId)).isEqualByComparingTo(ledgerBalance(walletId));
    assertThat(unbalancedTransactions(walletId)).isZero();
  }
}
