package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.support.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Reusing an {@code Idempotency-Key} of {@code POST /transfers} after its record is gone (owner,
 * 2026-11-11, option a): exactly as deposits and withdrawals under Q14 (documentations/08 §4B.2;
 * {@code MoneyFlowIT} "after a purge and after expiry"). The transfer row with the same {@code
 * (sender, key)} guards the key: the same sender wallet, recipient wallet, amount and currency get
 * the creation response again ({@code 201} body, or the {@code 403 RISK_BLOCKED} of a {@code
 * BLOCKED} row), anything else {@code 409 IDEMPOTENCY_KEY_REUSED}; the description is not compared.
 * Neither answer assesses, writes or audits anything. A stored refusal left no transfer row, so
 * after its record is gone the key runs a new request, as for deposits.
 */
@Import({TransferFlowSupport.Beans.class, TransferKeyAfterRetentionIT.Clocks.class})
class TransferKeyAfterRetentionIT extends TransferReviewFlowSupport {

  @TestConfiguration
  static class Clocks {
    @Bean
    @Primary
    MutableClock transferRetentionTestClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock mutableClock;

  /** How the key's record stops counting. */
  enum Gone {
    /** The purge deleted it. */
    PURGED,
    /** 24 hours passed (D04): it counts as absent even though the purge has not run. */
    EXPIRED
  }

  /** The first outcome of the key. */
  enum Outcome {
    COMPLETED(ALLOW, 201),
    PENDING_REVIEW(HOLD, 201),
    BLOCKED(BLOCK, 403);

    final String amount;
    final int status;

    Outcome(String amount, int status) {
      this.amount = amount;
      this.status = status;
    }
  }

  @BeforeEach
  void realTime() {
    mutableClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
  }

  /** Makes the key's record stop counting; returns the sender with a session valid now. */
  private Account forget(Gone gone, Account sender, UUID key) throws Exception {
    if (gone == Gone.PURGED) {
      jdbc.update(
          "DELETE FROM transfer.idempotency_records WHERE customer_id = ? AND idempotency_key = ?",
          sender.id(),
          key);
      assertThat(transferRecords(sender, key)).isZero();
      return sender;
    }
    mutableClock.advance(Duration.ofHours(24).plusSeconds(1));
    assertThat(transferRecords(sender, key)).as("expired, not purged").isEqualTo(1);
    return signedInAgain(sender);
  }

  /** A new session and token for the same customer at the clock's current time. */
  private Account signedInAgain(Account account) throws Exception {
    UUID session = UUID.randomUUID();
    Instant now = mutableClock.instant();
    insert(
        "auth.sessions",
        row("id", session, "user_id", account.id(), "last_seen_at", now.atOffset(ZoneOffset.UTC)));
    return new Account(
        account.id(),
        issuer.issue(account.id(), Role.CUSTOMER, session, now, now.plus(Duration.ofMinutes(15))));
  }

  private MockHttpServletResponse post(
      Account sender, Account recipient, String amount, String description, UUID key)
      throws Exception {
    return postTransfer(
        transferBody(numberOf(recipient), amount, description), sender.token(), key, randomIp());
  }

  private int investigations(Account sender) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM risk.investigations i JOIN risk.risk_assessments a"
            + " ON a.id = i.assessment_id WHERE a.customer_id = ?",
        Integer.class,
        sender.id());
  }

  private int ledgerTransactionsOf(Account account) {
    return jdbc.queryForObject(
        """
        SELECT count(DISTINCT e.transaction_id)
        FROM ledger.ledger_entries e JOIN ledger.ledger_accounts a ON a.id = e.account_id
        WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER'
        """,
        Integer.class,
        walletId(account));
  }

  /** Everything the request could have written for the sender, the recipient and the key. */
  private record Snapshot(
      List<Map<String, Object>> transfers,
      int assessments,
      int investigations,
      int audits,
      int senderPostings,
      int recipientPostings,
      BigDecimal senderAvailable,
      BigDecimal senderReserved,
      BigDecimal recipientAvailable,
      BigDecimal used,
      BigDecimal reservedUsage,
      int records) {}

  private Snapshot snapshot(Account sender, Account recipient, UUID key) {
    return new Snapshot(
        transfersOf(sender),
        assessments(sender),
        investigations(sender),
        transferAudits(sender).size(),
        ledgerTransactionsOf(sender),
        ledgerTransactionsOf(recipient),
        available(sender),
        reserved(sender),
        available(recipient),
        transferUsed(sender),
        transferReserved(sender),
        transferRecords(sender, key));
  }

  @ParameterizedTest(name = "{0} after the record was purged")
  @EnumSource(Outcome.class)
  void theSameTransferGetsItsCreationResponseAfterThePurge(Outcome outcome) throws Exception {
    sameTransferGetsItsCreationResponse(outcome, Gone.PURGED);
  }

  @ParameterizedTest(name = "{0} after the record expired")
  @EnumSource(Outcome.class)
  void theSameTransferGetsItsCreationResponseAfterExpiry(Outcome outcome) throws Exception {
    sameTransferGetsItsCreationResponse(outcome, Gone.EXPIRED);
  }

  private void sameTransferGetsItsCreationResponse(Outcome outcome, Gone gone) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    MockHttpServletResponse first = post(sender, recipient, outcome.amount, "rent", key);
    assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(outcome.status);
    assertThat(transfersOf(sender)).hasSize(1);
    assertThat(transfersOf(sender).get(0)).containsEntry("status", outcome.name());
    assertThat(assessments(sender)).isEqualTo(1);
    assertThat(transferAudits(sender)).hasSize(1);

    sender = forget(gone, sender, key);
    Snapshot before = snapshot(sender, recipient, key);

    // The same transfer, with another description and the amount written with a trailing zero.
    MockHttpServletResponse same =
        post(sender, recipient, outcome.amount + "0", "another note", key);

    assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(outcome.status);
    if (outcome == Outcome.BLOCKED) {
      assertThat(stable(same)).isEqualTo(stable(first));
    } else {
      assertThat(body(same)).isEqualTo(body(first));
    }
    // No new assessment, investigation, transfer, hold, posting, audit row or record.
    assertThat(snapshot(sender, recipient, key)).isEqualTo(before);
    if (outcome == Outcome.COMPLETED) {
      assertThat(ledgerTransactions(body(first).get("reference").asString())).isEqualTo(1);
    }
    assertTotalMatchesLedger(sender);
    assertTotalMatchesLedger(recipient);
  }

  @ParameterizedTest(name = "another transfer after the record was {0}")
  @EnumSource(Gone.class)
  void anotherTransferWithTheKeyIsKeyReusedAndWritesNothing(Gone gone) throws Exception {
    for (Outcome outcome : Outcome.values()) {
      Account sender = funded("3000.00");
      Account recipient = withWallet();
      Account other = withWallet();
      UUID key = UUID.randomUUID();
      assertThat(post(sender, recipient, outcome.amount, "rent", key).getStatus())
          .isEqualTo(outcome.status);
      sender = forget(gone, sender, key);
      Snapshot before = snapshot(sender, recipient, key);

      String otherAmount = new BigDecimal(outcome.amount).add(new BigDecimal("0.01")).toString();
      for (MockHttpServletResponse reused :
          List.of(
              post(sender, recipient, otherAmount, "rent", key),
              post(sender, other, outcome.amount, "rent", key))) {
        assertThat(reused.getStatus()).as(outcome + " " + gone).isEqualTo(409);
        assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
      }
      assertThat(snapshot(sender, recipient, key)).isEqualTo(before);
      assertThat(available(other)).isEqualByComparingTo("0.00");
      assertThat(ledgerTransactionsOf(other)).isZero();
      realTime();
    }
  }

  /** As a deposit's stored refusal: no transfer row, so the key runs a new request. */
  @ParameterizedTest(name = "a stored refusal after the record was {0}")
  @EnumSource(Gone.class)
  void aStoredRefusalLeftNoTransferSoTheKeyRunsAgain(Gone gone) throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    MockHttpServletResponse refused = post(sender, recipient, "500.00", "rent", key);
    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(errorCode(refused)).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(transfersOf(sender)).isEmpty();
    assertThat(assessments(sender)).isZero();
    fund(sender, "1000.00");
    // While the record counts, the refusal is replayed.
    assertThat(stable(post(sender, recipient, "500.00", "rent", key))).isEqualTo(stable(refused));

    sender = forget(gone, sender, key);
    MockHttpServletResponse fresh = post(sender, recipient, "500.00", "rent", key);

    assertThat(fresh.getStatus()).as(fresh.getContentAsString()).isEqualTo(201);
    assertThat(body(fresh).get("status").asString()).isEqualTo("COMPLETED");
    assertThat(transfersOf(sender)).hasSize(1);
    assertThat(assessments(sender)).isEqualTo(1);
    assertBalances(sender, "600.00", "0.00");
    assertBalances(recipient, "500.00", "0.00");
    assertThat(transferRecords(sender, key)).isEqualTo(1);
  }

  // The in-transaction guard against a real database (security review L1) ------------------------

  /** The key's record as stored, every column. */
  private Map<String, Object> record(Account sender, UUID key) {
    return jdbc.queryForMap(
        "SELECT request_hash, response_status, response_body::text AS response_body,"
            + " created_at, expires_at FROM transfer.idempotency_records"
            + " WHERE customer_id = ? AND operation = 'TRANSFER' AND idempotency_key = ?",
        sender.id(),
        key);
  }

  /**
   * The early check misses the transfer (the spy answers empty once), so the request reaches the
   * engine, which replaces the expired record under its lock and runs the money transaction. The
   * guard, the first step of that transaction, finds the row through the real repository: the
   * transaction rolls back, so the expired record is exactly as before, and the request gets the
   * Q14 answer. The risk assessment ran before that transaction, in its own (Q3 step 6), so it
   * stays as an orphan, as for two concurrent requests with one key (Q4); nothing else is written.
   */
  @org.junit.jupiter.api.Test
  void theGuardInTheMoneyTransactionRollsBackTheReplacedRecord() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    MockHttpServletResponse first = post(sender, recipient, ALLOW, "rent", key);
    assertThat(first.getStatus()).isEqualTo(201);
    sender = forget(Gone.EXPIRED, sender, key);
    Map<String, Object> expiredRecord = record(sender, key);
    Snapshot before = snapshot(sender, recipient, key);
    org.mockito.Mockito.clearInvocations(transferRowsSpy);
    org.mockito.Mockito.doReturn(java.util.Optional.empty())
        .doCallRealMethod()
        .when(transferRowsSpy)
        .findBySenderAndKey(sender.id(), key);

    MockHttpServletResponse same = post(sender, recipient, ALLOW, "rent", key);

    assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(201);
    assertThat(body(same)).isEqualTo(body(first));
    // Both checks ran: the early one (missed) and the guard in the money transaction (found).
    org.mockito.Mockito.verify(transferRowsSpy, org.mockito.Mockito.times(2))
        .findBySenderAndKey(sender.id(), key);
    org.mockito.Mockito.verify(transferRowsSpy, org.mockito.Mockito.never())
        .insert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    assertThat(record(sender, key)).isEqualTo(expiredRecord);
    // Only the orphan assessment of the attempt is new (Q4), with no investigation (ALLOW).
    Snapshot after = snapshot(sender, recipient, key);
    assertThat(after.assessments()).isEqualTo(before.assessments() + 1);
    assertThat(after).usingRecursiveComparison().ignoringFields("assessments").isEqualTo(before);
    assertTotalMatchesLedger(sender);
    assertTotalMatchesLedger(recipient);
  }

  /**
   * The same through the stored-refusal hook: a frozen sender is refused by the pre-check ({@code
   * WALLET_FROZEN}, stored), so the engine replaces the expired record twice (the money transaction
   * and the refusal's own) and the guard runs first in the refusal's hook. It finds the transfer:
   * the refusal is not stored and not audited, the record is as before, and no assessment runs.
   */
  @org.junit.jupiter.api.Test
  void theGuardInTheRefusalHookStoresNoRefusalAndNoAuditRow() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    MockHttpServletResponse first = post(sender, recipient, ALLOW, "rent", key);
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(freeze(sender).getStatus()).isEqualTo(200);
    sender = forget(Gone.EXPIRED, sender, key);
    Map<String, Object> expiredRecord = record(sender, key);
    Snapshot before = snapshot(sender, recipient, key);
    org.mockito.Mockito.clearInvocations(transferRowsSpy);
    org.mockito.Mockito.doReturn(java.util.Optional.empty())
        .doCallRealMethod()
        .when(transferRowsSpy)
        .findBySenderAndKey(sender.id(), key);

    MockHttpServletResponse same = post(sender, recipient, ALLOW, "rent", key);

    assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(201);
    assertThat(body(same)).isEqualTo(body(first));
    org.mockito.Mockito.verify(transferRowsSpy, org.mockito.Mockito.times(2))
        .findBySenderAndKey(sender.id(), key);
    assertThat(record(sender, key)).isEqualTo(expiredRecord);
    assertThat(snapshot(sender, recipient, key)).isEqualTo(before);
    assertTotalMatchesLedger(sender);
  }
}
