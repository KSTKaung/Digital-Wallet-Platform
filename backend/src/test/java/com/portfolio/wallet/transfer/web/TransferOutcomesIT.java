package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * The four outcomes of {@code POST /transfers} by risk band (documentations/02 §2.4; doc 06
 * MVP-ADR-06, -22, -24; doc 08 §4C.2 Q3, Q4, §4C.4 criteria 3, 6, 9), with the test policy of
 * {@link TransferReviewFlowSupport}: the boundaries 39/40, 69/70 and 89/90, the hold, the {@code
 * BLOCKED} row with its open investigation and the stored {@code RISK_BLOCKED}, and the replay of
 * every outcome.
 */
class TransferOutcomesIT extends TransferReviewFlowSupport {

  private static final LocalDate TODAY_IN_BANGKOK = LocalDate.now(ZoneId.of("Asia/Bangkok"));

  @ParameterizedTest(name = "{0} THB scores {1}: {2}")
  @CsvSource({
    "1000.00, 39, ALLOW, 201, COMPLETED",
    "1100.00, 40, MONITOR, 201, COMPLETED",
    "1200.00, 69, MONITOR, 201, COMPLETED",
    "1300.00, 70, MANUAL_REVIEW, 201, PENDING_REVIEW",
    "1400.00, 89, MANUAL_REVIEW, 201, PENDING_REVIEW",
    "1500.00, 90, BLOCK, 403, BLOCKED"
  })
  void eachBandBoundaryGivesItsOutcome(
      String amount, int score, String decision, int status, String transferStatus)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();

    MockHttpServletResponse response = transfer(sender, recipient, amount);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    List<Map<String, Object>> rows = transfersOf(sender);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("status", transferStatus);
    UUID id = (UUID) rows.get(0).get("id");
    Map<String, Object> assessment =
        jdbc.queryForMap(
            "SELECT score, decision FROM risk.risk_assessments WHERE transfer_id = ?", id);
    assertThat(assessment).containsEntry("score", score).containsEntry("decision", decision);
  }

  // MANUAL_REVIEW (criterion 6) -----------------------------------------------------------------

  @Test
  void aHoldReservesTheAmountAndTheLimitAndPostsNothing() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    String ip = randomIp();

    MockHttpServletResponse response =
        postTransfer(transferBody(numberOf(recipient), HOLD, "rent"), sender.token(), key, ip);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = body(response);
    assertThat(body.get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertThat(body.get("ledgerStatus").isNull()).isTrue();
    assertThat(body.get("direction").asString()).isEqualTo("SENT");
    UUID id = transferId(response);
    String reference = body.get("reference").asString();

    assertBalances(sender, "700.00", "1300.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(ledgerTransactions(reference)).isZero();
    List<Map<String, Object>> usage = transferUsage(sender, TODAY_IN_BANGKOK);
    assertThat(usage).hasSize(1);
    assertThat((BigDecimal) usage.get(0).get("used")).isEqualByComparingTo("0");
    assertThat((BigDecimal) usage.get(0).get("reserved")).isEqualByComparingTo("1300.00");

    Map<String, Object> row = transferRow(id);
    assertThat(row)
        .containsEntry("status", "PENDING_REVIEW")
        .containsEntry("limit_date", java.sql.Date.valueOf(TODAY_IN_BANGKOK));
    assertThat(row.get("ledger_status")).isNull();
    assertThat(row.get("decided_at")).isNull();
    // The database set held_at; the deadline is exactly 24 hours later (MVP-ADR-24).
    Instant heldAt = instant(row.get("held_at"));
    Instant deadline = instant(row.get("review_expires_at"));
    assertThat(Duration.between(heldAt, deadline)).isEqualTo(Duration.ofHours(24));
    assertThat(Instant.parse(body.get("reviewExpiresAt").asString())).isEqualTo(deadline);
    assertThat(Instant.parse(body.get("createdAt").asString()))
        .isEqualTo(instant(row.get("created_at")));

    List<Map<String, Object>> audits = transferAudits(sender);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0))
        .containsEntry("action", "TRANSFER")
        .containsEntry("result", "SUCCESS")
        .containsEntry("resource_id", id.toString())
        .containsEntry("ip", ip);
    assertThat((String) audits.get(0).get("new_value"))
        .contains("\"status\": \"PENDING_REVIEW\"")
        .doesNotContain("rent")
        .doesNotContain("score");

    // The replay returns the held transfer and holds nothing more.
    MockHttpServletResponse replay =
        postTransfer(transferBody(numberOf(recipient), HOLD, "rent"), sender.token(), key, ip);
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(body(replay)).isEqualTo(body);
    assertBalances(sender, "700.00", "1300.00");
    assertThat(transfersOf(sender)).hasSize(1);
  }

  // BLOCK (criterion 3) ---------------------------------------------------------------------------

  @Test
  void aBlockKeepsTheBlockedRowOpensAnInvestigationAndStoresRiskBlocked() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    String json = transferBody(numberOf(recipient), BLOCK, "gift");

    MockHttpServletResponse blocked = postTransfer(json, sender.token(), key, randomIp());

    assertThat(blocked.getStatus()).isEqualTo(403);
    assertThat(errorCode(blocked)).isEqualTo("RISK_BLOCKED");
    // Names no rule, score or threshold (doc 08 §5, step 2 notes).
    assertThat(body(blocked).get("message").asString()).isEqualTo("The transfer was refused");

    List<Map<String, Object>> rows = transfersOf(sender);
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    UUID id = (UUID) row.get("id");
    assertThat(row)
        .containsEntry("status", "BLOCKED")
        .containsEntry("limit_date", java.sql.Date.valueOf(TODAY_IN_BANGKOK))
        .containsEntry("idempotency_key", key);
    assertThat(row.get("ledger_status")).isNull();
    assertThat(row.get("held_at")).isNull();
    UUID assessment = (UUID) row.get("risk_assessment_id");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM risk.investigations WHERE assessment_id = ?",
                String.class,
                assessment))
        .isEqualTo("OPEN");
    // No balance or limit changes.
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferUsage(sender, TODAY_IN_BANGKOK)).isEmpty();
    List<Map<String, Object>> audits = transferAudits(sender);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0))
        .containsEntry("result", "FAILURE")
        .containsEntry("resource_id", id.toString());
    assertThat((String) audits.get(0).get("new_value"))
        .contains("\"status\": \"BLOCKED\"")
        .contains("\"errorCode\": \"RISK_BLOCKED\"")
        .doesNotContain("gift");
    assertThat(transferRecords(sender, key)).isEqualTo(1);

    // Stored with its row: the replay is the same 403, and nothing more is written.
    MockHttpServletResponse replay = postTransfer(json, sender.token(), key, randomIp());
    assertThat(replay.getStatus()).isEqualTo(403);
    assertThat(stable(replay)).isEqualTo(stable(blocked));
    assertThat(transfersOf(sender)).hasSize(1);
    assertThat(assessments(sender)).isEqualTo(1);
    assertThat(transferAudits(sender)).hasSize(1);
    MockHttpServletResponse reused =
        postTransfer(
            transferBody(numberOf(recipient), ALLOW, "gift"), sender.token(), key, randomIp());
    assertThat(reused.getStatus()).isEqualTo(409);
    assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  // A refusal under the locks (Q3 step 7, Q4) -----------------------------------------------------

  @Test
  void aRefusalFoundUnderTheLocksIsStoredAndItsAssessmentStays() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    // The sender is frozen after the pre-check passed: the money transaction finds it.
    doAnswer(
            invocation -> {
              withTriggersDisabled(
                  superuser ->
                      update(
                          superuser,
                          "UPDATE wallet.wallets SET status = 'FROZEN' WHERE customer_id = ?",
                          sender.id()));
              return invocation.callRealMethod();
            })
        .when(transferRowsSpy)
        .completedTransferExists(sender.id(), recipient.id());

    MockHttpServletResponse refused = transfer(sender, recipient, HOLD, key);

    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(errorCode(refused)).isEqualTo("WALLET_FROZEN");
    assertThat(transfersOf(sender)).isEmpty();
    assertBalances(sender, "2000.00", "0.00");
    assertThat(transferRecords(sender, key)).isEqualTo(1);
    // The assessment stays (Q4) and the refusal has its one audit row, naming the transfer id.
    assertThat(assessments(sender)).isEqualTo(1);
    List<Map<String, Object>> audits = transferAudits(sender);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0)).containsEntry("result", "FAILURE");
    assertThat(audits.get(0).get("resource_id"))
        .isEqualTo(
            jdbc.queryForObject(
                    "SELECT transfer_id FROM risk.risk_assessments WHERE customer_id = ?",
                    UUID.class,
                    sender.id())
                .toString());
    org.mockito.Mockito.reset(transferRowsSpy);
    MockHttpServletResponse replay = transfer(sender, recipient, HOLD, key);
    assertThat(stable(replay)).isEqualTo(stable(refused));
    assertThat(assessments(sender)).isEqualTo(1);
  }

  // No duplicate rows on replays (owner, 2026-11-11) ---------------------------------------------

  /**
   * A {@code PENDING_REVIEW} transfer replayed within the window with the same key and body: the
   * identical {@code 201}, and no second {@code TRANSFER} audit row, hold, assessment, review row
   * or email; a different body with that key is {@code 409} and writes nothing either.
   */
  @Test
  void aHeldTransferReplayWritesNoSecondAuditHoldOrAssessment() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    String json = transferBody(numberOf(recipient), HOLD, "rent");

    MockHttpServletResponse held = postTransfer(json, sender.token(), key, randomIp());
    assertThat(held.getStatus()).isEqualTo(201);
    assertThat(body(held).get("status").asString()).isEqualTo("PENDING_REVIEW");
    UUID id = transferId(held);
    Map<String, Object> before = sideEffects(sender, recipient, id);
    assertThat(transferAudits(sender)).hasSize(1);

    for (int i = 0; i < 2; i++) {
      MockHttpServletResponse replay = postTransfer(json, sender.token(), key, randomIp());
      assertThat(replay.getStatus()).isEqualTo(201);
      assertThat(body(replay)).isEqualTo(body(held));
    }
    MockHttpServletResponse reused =
        postTransfer(
            transferBody(numberOf(recipient), ALLOW, "rent"), sender.token(), key, randomIp());
    assertThat(reused.getStatus()).isEqualTo(409);
    assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    assertThat(sideEffects(sender, recipient, id)).isEqualTo(before);
    assertThat(transferAudits(sender)).hasSize(1);
    assertThat(assessments(sender)).isEqualTo(1);
    assertThat(transfersOf(sender)).hasSize(1);
    assertBalances(sender, "700.00", "1300.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("1300.00");
    assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");
  }

  /**
   * {@code 409 IDEMPOTENCY_KEY_REUSED} after a {@code COMPLETED} and after a {@code BLOCKED}
   * original writes no {@code TRANSFER} audit row (nor any other row of the sender's).
   */
  @ParameterizedTest(name = "after a {1} original")
  @CsvSource({"999.99, COMPLETED, 201", "1500.00, BLOCKED, 403"})
  void aReusedKeyWritesNoAuditRow(String amount, String status, int originalStatus)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();

    MockHttpServletResponse original =
        postTransfer(
            transferBody(numberOf(recipient), amount, "gift"), sender.token(), key, randomIp());
    assertThat(original.getStatus()).as(original.getContentAsString()).isEqualTo(originalStatus);
    List<Map<String, Object>> rows = transfersOf(sender);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("status", status);
    UUID id = (UUID) rows.get(0).get("id");
    Map<String, Object> before = sideEffects(sender, recipient, id);
    List<Map<String, Object>> auditsBefore = transferAudits(sender);
    assertThat(auditsBefore).hasSize(1);
    BigDecimal availableBefore = available(sender);

    for (String other :
        List.of(
            transferBody(numberOf(recipient), "1.00", "gift"),
            transferBody(numberOf(recipient), amount, "other"),
            transferBody(numberOf(recipient), amount, null))) {
      MockHttpServletResponse reused = postTransfer(other, sender.token(), key, randomIp());
      assertThat(reused.getStatus()).as(reused.getContentAsString()).isEqualTo(409);
      assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    assertThat(transferAudits(sender)).isEqualTo(auditsBefore);
    assertThat(sideEffects(sender, recipient, id)).isEqualTo(before);
    assertThat(transfersOf(sender)).hasSize(1);
    assertThat(assessments(sender)).isEqualTo(1);
    assertThat(available(sender)).isEqualByComparingTo(availableBefore);
    assertTotalMatchesLedger(sender);
  }

  /**
   * Counts of every row a create can write for these parties: all audit rows of the sender, the
   * sender's assessments, transfers, idempotency records, review rows of the transfer, emails to
   * either party, and the sender's reserved balance and limit usage.
   */
  private Map<String, Object> sideEffects(Account sender, Account recipient, UUID transferId) {
    return jdbc.queryForMap(
        """
        SELECT
          (SELECT count(*) FROM audit.audit_logs WHERE actor_id = ?) AS audits,
          (SELECT count(*) FROM risk.risk_assessments WHERE customer_id = ?) AS assessments,
          (SELECT count(*) FROM transfer.transfers WHERE sender_customer_id = ?) AS transfers,
          (SELECT count(*) FROM transfer.idempotency_records WHERE customer_id = ?) AS records,
          (SELECT count(*) FROM risk.risk_reviews WHERE transfer_id = ?) AS reviews,
          (SELECT count(*) FROM risk.investigations i
             JOIN risk.risk_assessments a ON a.id = i.assessment_id
             WHERE a.customer_id = ?) AS investigations,
          (SELECT count(*) FROM notification.notifications WHERE user_id IN (?, ?)) AS emails,
          (SELECT reserved_balance FROM wallet.wallets WHERE customer_id = ?) AS reserved,
          (SELECT coalesce(sum(used), 0) || '/' || coalesce(sum(reserved), 0)
             FROM wallet.daily_limit_usage u JOIN wallet.wallets w ON w.id = u.wallet_id
             WHERE w.customer_id = ? AND u.operation = 'TRANSFER') AS usage
        """,
        sender.id(),
        sender.id(),
        sender.id(),
        sender.id(),
        transferId,
        sender.id(),
        sender.id(),
        recipient.id(),
        sender.id(),
        sender.id());
  }

  private static Instant instant(Object timestamp) {
    return ((Timestamp) timestamp).toInstant();
  }
}
