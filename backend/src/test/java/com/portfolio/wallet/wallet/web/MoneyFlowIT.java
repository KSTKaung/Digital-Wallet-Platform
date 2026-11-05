package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.auth.domain.Role;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Deposits and withdrawals over HTTP (documentations/02 §1.3, §1.4, §2.3; doc 06 MVP-ADR-03, -06,
 * -10, -13, -18, -21, -22; doc 08 §4B.4 AC 1, 5, 6, 8, 9, 11; Q14).
 */
@ExtendWith(OutputCaptureExtension.class)
class MoneyFlowIT extends MoneyFlowSupport {

  // Success -------------------------------------------------------------------------------------

  @Test
  void aDepositUpdatesBalanceLedgerBusinessRowAndAuditTogether() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    String number = (String) walletOf(customer.id()).get("wallet_number");
    UUID key = UUID.randomUUID();

    MockHttpServletResponse response =
        sendWithCorrelation(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(DEPOSITS)
                .contentType("application/json")
                .content("{\"amount\":500.5,\"currency\":\"THB\",\"reference\":\"salary\"}")
                .header(KEY_HEADER, key.toString()),
            customer.token(),
            "corr-deposit-1");

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = body(response);
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "id", "walletNumber", "amount", "status", "reference", "ledgerStatus");
    assertThat(body.get("walletNumber").asString()).isEqualTo(number);
    assertThat(body.get("amount").isNumber()).isTrue();
    assertThat(response.getContentAsString()).contains("\"amount\":500.50");
    assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(body.get("ledgerStatus").asString()).isEqualTo("POSTED");
    String reference = body.get("reference").asString();
    assertThat(reference).matches("DEP-[0-9A-F]{32}");

    assertThat(available(wallet)).isEqualByComparingTo("500.50");
    assertReconciled(wallet);
    assertThat(ledgerTransactions(wallet)).isEqualTo(1);
    Map<String, Object> posting =
        jdbc.queryForMap(
            "SELECT transaction_type, correlation_id FROM ledger.financial_transactions"
                + " WHERE transaction_reference = ?",
            reference);
    assertThat(posting).containsEntry("transaction_type", "DEPOSIT");
    assertThat(posting).containsEntry("correlation_id", "corr-deposit-1");
    // DR SYS-CASH-CLEARING / CR customer (doc 01 §6.4).
    assertThat(
            jdbc.queryForList(
                "SELECT coalesce(a.code, a.account_type) || ':' || e.entry_type"
                    + " FROM ledger.ledger_entries e"
                    + " JOIN ledger.ledger_accounts a ON a.id = e.account_id"
                    + " JOIN ledger.financial_transactions t ON t.id = e.transaction_id"
                    + " WHERE t.transaction_reference = ?",
                String.class,
                reference))
        .containsExactlyInAnyOrder("SYS-CASH-CLEARING:DEBIT", "CUSTOMER:CREDIT");

    Map<String, Object> row =
        movementRow("wallet.deposits", UUID.fromString(body.get("id").asString()));
    assertThat(row)
        .containsEntry("status", "COMPLETED")
        .containsEntry("ledger_status", "POSTED")
        .containsEntry("reference", reference)
        .containsEntry("client_reference", "salary")
        .containsEntry("idempotency_key", key)
        .containsEntry("correlation_id", "corr-deposit-1");
    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("500.50");
    assertThat(usedToday(wallet, "DEPOSIT")).isEqualByComparingTo("500.50");

    List<Map<String, Object>> audit = auditRows(body.get("id").asString());
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0))
        .containsEntry("action", "DEPOSIT")
        .containsEntry("actor_id", customer.id())
        .containsEntry("result", "SUCCESS")
        .containsEntry("correlation_id", "corr-deposit-1");
    assertThat((String) audit.get(0).get("new_value"))
        .doesNotContain("salary")
        .doesNotContain(number);
    assertThat(idempotencyRecords(customer.id(), key)).isEqualTo(1);
  }

  @Test
  void aWithdrawalPostsTheReverseAndLeavesSnapshotAndLedgerInStep() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000.00");

    MockHttpServletResponse response = withdraw(customer, "250.25", UUID.randomUUID());

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = body(response);
    assertThat(body.get("reference").asString()).startsWith("WDR-");
    assertThat(response.getContentAsString()).contains("\"amount\":250.25");
    assertThat(available(wallet)).isEqualByComparingTo("749.75");
    assertReconciled(wallet);
    assertThat(ledgerTransactions(wallet)).isEqualTo(2);
    assertThat(movementRow("wallet.withdrawals", UUID.fromString(body.get("id").asString())))
        .containsEntry("ledger_status", "POSTED");
    assertThat(usedToday(wallet, "WITHDRAWAL")).isEqualByComparingTo("250.25");
  }

  @Test
  void trailingZerosAreAcceptedAsTwoDecimals() throws Exception {
    Account customer = withWallet();

    MockHttpServletResponse response = deposit(customer, "100.0000", UUID.randomUUID());

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).contains("\"amount\":100.00");
  }

  // Requests refused before the key is used -------------------------------------------------------

  @Test
  void requestErrorsLeaveNoRecordAndMoveNoMoney() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();
    String token = customer.token();

    record Case(String body, UUID key, String keyText, int status, String code) {}
    List<Case> cases =
        List.of(
            new Case("{\"amount\":100,\"currency\":\"THB\"}", null, null, 400, "VALIDATION_ERROR"),
            new Case(
                "{\"amount\":100,\"currency\":\"THB\"}",
                null,
                "1-1-1-1-1",
                400,
                "VALIDATION_ERROR"),
            new Case("{\"currency\":\"THB\"}", key, null, 400, "VALIDATION_ERROR"),
            new Case("{\"amount\":100}", key, null, 400, "VALIDATION_ERROR"),
            new Case("{\"amount\":100.001,\"currency\":\"THB\"}", key, null, 400, "INVALID_AMOUNT"),
            new Case("{\"amount\":0,\"currency\":\"THB\"}", key, null, 400, "INVALID_AMOUNT"),
            new Case("{\"amount\":-5,\"currency\":\"THB\"}", key, null, 400, "INVALID_AMOUNT"),
            new Case("{\"amount\":100,\"currency\":\"USD\"}", key, null, 400, "CURRENCY_MISMATCH"),
            new Case(
                "{\"amount\":100,\"currency\":\"THB\",\"reference\":\"a\\u0007b\"}",
                key,
                null,
                400,
                "VALIDATION_ERROR"),
            new Case(
                "{\"amount\":100,\"currency\":\"THB\",\"reference\":\"" + "x".repeat(101) + "\"}",
                key,
                null,
                400,
                "VALIDATION_ERROR"),
            // Below the minimum: checked under the lock, but not a stored rejection.
            new Case("{\"amount\":99.99,\"currency\":\"THB\"}", key, null, 400, "INVALID_AMOUNT"));
    for (Case c : cases) {
      var request =
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(DEPOSITS)
              .contentType("application/json")
              .content(c.body());
      if (c.key() != null) {
        request.header(KEY_HEADER, c.key().toString());
      }
      if (c.keyText() != null) {
        request.header(KEY_HEADER, c.keyText());
      }
      MockHttpServletResponse response = send(request, token);
      assertThat(response.getStatus()).as(c.body()).isEqualTo(c.status());
      assertThat(errorCode(response)).as(c.body()).isEqualTo(c.code());
      assertThat(response.getContentAsString()).doesNotContain("100.001").doesNotContain("USD");
    }

    assertThat(idempotencyRecords(customer.id(), key)).isZero();
    assertThat(available(wallet)).isEqualByComparingTo("0.00");
    assertThat(ledgerTransactions(wallet)).isZero();
    // The key was never consumed: a valid request with it goes through.
    assertThat(deposit(customer, "100", key).getStatus()).isEqualTo(201);
  }

  /**
   * Y2 and Y3: an amount NUMERIC(19,2) cannot hold is "too large", not "too many decimals", for
   * both operations, and the amount is judged before the currency. The value is never echoed.
   */
  @Test
  void tooLargeAmountsAndAmountBeforeCurrency() throws Exception {
    Account customer = withWallet();
    UUID key = UUID.randomUUID();

    for (String path : List.of(DEPOSITS, WITHDRAWALS)) {
      MockHttpServletResponse tooLarge =
          postMoney(
              path, "{\"amount\":123456789012345678,\"currency\":\"THB\"}", customer.token(), key);
      assertThat(tooLarge.getStatus()).as(path).isEqualTo(400);
      assertThat(errorCode(tooLarge)).isEqualTo("INVALID_AMOUNT");
      assertThat(body(tooLarge).get("message").asString()).isEqualTo("amount: is too large");
      assertThat(tooLarge.getContentAsString()).doesNotContain("123456789012345678");

      MockHttpServletResponse zeroUsd =
          postMoney(path, "{\"amount\":0,\"currency\":\"USD\"}", customer.token(), key);
      assertThat(zeroUsd.getStatus()).isEqualTo(400);
      assertThat(errorCode(zeroUsd)).isEqualTo("INVALID_AMOUNT");
    }
    assertThat(idempotencyRecords(customer.id(), key)).isZero();
  }

  /** AC 1: no wallet before approval; KYC_REQUIRED does not consume the key (doc 02 §1.4). */
  @Test
  void beforeApprovalMoneyEndpointsAreKycRequiredAndTheKeyStaysUsable() throws Exception {
    Account customer = customer();
    UUID key = UUID.randomUUID();

    for (MockHttpServletResponse response :
        List.of(deposit(customer, "100", key), withdraw(customer, "100", key))) {
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(errorCode(response)).isEqualTo("KYC_REQUIRED");
    }
    assertThat(idempotencyRecords(customer.id(), key)).isZero();

    UUID application = submitted(customer, "BASIC");
    String officer = account(Role.COMPLIANCE_OFFICER).token();
    assertThat(
            postAs("/api/v1/admin/kyc/applications/" + application + "/approve", null, officer)
                .getStatus())
        .isEqualTo(200);
    assertThat(deposit(customer, "100", key).getStatus()).isEqualTo(201);
  }

  @Test
  void staffAreRefusedAndAnonymousCallersGetTheGeneric401() throws Exception {
    for (Role role : List.of(Role.ADMIN, Role.COMPLIANCE_OFFICER)) {
      MockHttpServletResponse response = deposit(account(role), "100", UUID.randomUUID());
      assertThat(response.getStatus()).as(role.name()).isEqualTo(403);
    }
    MockHttpServletResponse anonymous =
        postMoney(WITHDRAWALS, "{\"amount\":100,\"currency\":\"THB\"}", null, UUID.randomUUID());
    assertThat(anonymous.getStatus()).isEqualTo(401);
    assertThat(stable(anonymous)).isEqualTo(stable(getAs("/api/v1/no-such-path", null)));
  }

  // Limits (MVP-ADR-18, AC 6) ---------------------------------------------------------------------

  @Test
  void depositLimitsAtMinimumMaximumAndDaily() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);

    assertThat(errorCode(deposit(customer, "99.99", UUID.randomUUID())))
        .isEqualTo("INVALID_AMOUNT");
    funded(customer, "100.00");
    assertThat(errorCode(deposit(customer, "100000.01", UUID.randomUUID())))
        .isEqualTo("SINGLE_LIMIT_EXCEEDED");
    funded(customer, "100000.00");
    // Used today: 100,100.00 of 200,000.00.
    MockHttpServletResponse over = deposit(customer, "99900.01", UUID.randomUUID());
    assertThat(over.getStatus()).isEqualTo(409);
    assertThat(errorCode(over)).isEqualTo("DAILY_LIMIT_EXCEEDED");
    funded(customer, "99900.00");

    assertThat(available(wallet)).isEqualByComparingTo("200000.00");
    assertThat(usedToday(wallet, "DEPOSIT")).isEqualByComparingTo("200000.00");
    assertReconciled(wallet);
  }

  @Test
  void withdrawalLimitsAtMinimumMaximumDailyAndBalance() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "100000.00");

    assertThat(errorCode(withdraw(customer, "99.99", UUID.randomUUID())))
        .isEqualTo("INVALID_AMOUNT");
    assertThat(errorCode(withdraw(customer, "30000.01", UUID.randomUUID())))
        .isEqualTo("SINGLE_LIMIT_EXCEEDED");
    assertThat(withdraw(customer, "30000.00", UUID.randomUUID()).getStatus()).isEqualTo(201);
    assertThat(withdraw(customer, "100.00", UUID.randomUUID()).getStatus()).isEqualTo(201);
    // Used today: 30,100.00 of 50,000.00.
    assertThat(errorCode(withdraw(customer, "19900.01", UUID.randomUUID())))
        .isEqualTo("DAILY_LIMIT_EXCEEDED");
    assertThat(withdraw(customer, "19900.00", UUID.randomUUID()).getStatus()).isEqualTo(201);

    assertThat(available(wallet)).isEqualByComparingTo("50000.00");
    assertReconciled(wallet);

    Account poor = withWallet();
    funded(poor, "150.00");
    assertThat(errorCode(withdraw(poor, "150.01", UUID.randomUUID())))
        .isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(withdraw(poor, "150.00", UUID.randomUUID()).getStatus()).isEqualTo(201);
    assertThat(available(walletId(poor))).isEqualByComparingTo("0.00");
  }

  /** MVP-ADR-22: usage resets at midnight Bangkok, 17:00 UTC. */
  @Test
  void theDailyLimitResetsAt1700Utc() throws Exception {
    mutableClock.set(Instant.parse("2031-03-10T16:58:00Z"));
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "100000.00");
    funded(customer, "100000.00");

    mutableClock.set(Instant.parse("2031-03-10T16:59:59Z"));
    customer = signedInAgain(customer);
    assertThat(errorCode(deposit(customer, "100.00", UUID.randomUUID())))
        .isEqualTo("DAILY_LIMIT_EXCEEDED");

    mutableClock.set(Instant.parse("2031-03-10T17:00:00Z"));
    assertThat(deposit(customer, "100.00", UUID.randomUUID()).getStatus()).isEqualTo(201);

    assertThat(
            jdbc.queryForList(
                "SELECT limit_date::text || '=' || used FROM wallet.daily_limit_usage"
                    + " WHERE wallet_id = ? AND operation = 'DEPOSIT' ORDER BY limit_date",
                String.class,
                wallet))
        .containsExactly("2031-03-10=200000.00", "2031-03-11=100.00");
  }

  // Idempotency (AC 8)
  // ------------------------------------------------------------------------------

  @Test
  void aReplayReturnsTheOriginalResponseAndMovesMoneyOnce() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();

    MockHttpServletResponse first = deposit(customer, "300.00", key);
    MockHttpServletResponse replay = deposit(customer, "300", key);

    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(body(replay)).isEqualTo(body(first));
    assertThat(available(wallet)).isEqualByComparingTo("300.00");
    assertThat(ledgerTransactions(wallet)).isEqualTo(1);
    assertThat(moneyAuditRows(customer.id())).isEqualTo(1);

    MockHttpServletResponse reused = deposit(customer, "300.01", key);
    assertThat(reused.getStatus()).isEqualTo(409);
    assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    // A key is per operation: the same key on a withdrawal is a different key.
    assertThat(withdraw(customer, "100", key).getStatus()).isEqualTo(201);
  }

  @Test
  void anotherCustomersIdenticalKeyIsIndependent() throws Exception {
    Account alice = withWallet();
    Account bob = withWallet();
    UUID key = UUID.randomUUID();

    assertThat(deposit(alice, "500", key).getStatus()).isEqualTo(201);
    MockHttpServletResponse bobs = deposit(bob, "700", key);

    assertThat(bobs.getStatus()).isEqualTo(201);
    assertThat(body(bobs).get("amount").decimalValue()).isEqualByComparingTo("700");
    assertThat(available(walletId(bob))).isEqualByComparingTo("700.00");
  }

  @Test
  void storedRejectionsAreReplayedEvenAfterTheCauseIsGone() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();

    MockHttpServletResponse rejected = withdraw(customer, "500", key);
    assertThat(rejected.getStatus()).isEqualTo(409);
    assertThat(errorCode(rejected)).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(idempotencyRecords(customer.id(), key)).isEqualTo(1);

    funded(customer, "1000");
    MockHttpServletResponse replay = withdraw(customer, "500", key);
    assertThat(replay.getStatus()).isEqualTo(409);
    assertThat(stable(replay)).isEqualTo(stable(rejected));
    assertThat(available(wallet)).isEqualByComparingTo("1000.00");
    // A new key runs fresh.
    assertThat(withdraw(customer, "500", UUID.randomUUID()).getStatus()).isEqualTo(201);
  }

  /** AC 9: a frozen wallet cannot transact; the refusal is stored like the other rejections. */
  @Test
  void aFrozenWalletCannotDepositOrWithdraw() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000");
    assertThat(postAs(FREEZE, null, customer.token()).getStatus()).isEqualTo(200);
    UUID key = UUID.randomUUID();

    for (MockHttpServletResponse response :
        List.of(deposit(customer, "100", key), withdraw(customer, "100", key))) {
      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(errorCode(response)).isEqualTo("WALLET_FROZEN");
    }
    assertThat(available(wallet)).isEqualByComparingTo("1000.00");
    assertThat(ledgerTransactions(wallet)).isEqualTo(1);
    assertThat(idempotencyRecords(customer.id(), key)).isEqualTo(2);
  }

  /** Q14: the record is gone (purged), the deposit row is not. */
  @Test
  void afterThePurgeTheSameMovementIsReturnedAndAnotherIsKeyReused() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    UUID key = UUID.randomUUID();
    MockHttpServletResponse first = deposit(customer, "400.00", key);
    jdbc.update(
        "DELETE FROM wallet.idempotency_records WHERE customer_id = ? AND idempotency_key = ?",
        customer.id(),
        key);

    MockHttpServletResponse same = deposit(customer, "400", key);
    assertThat(same.getStatus()).isEqualTo(201);
    assertThat(body(same)).isEqualTo(body(first));
    assertThat(available(wallet)).isEqualByComparingTo("400.00");
    assertThat(ledgerTransactions(wallet)).isEqualTo(1);

    jdbc.update(
        "DELETE FROM wallet.idempotency_records WHERE customer_id = ? AND idempotency_key = ?",
        customer.id(),
        key);
    MockHttpServletResponse other = deposit(customer, "400.01", key);
    assertThat(other.getStatus()).isEqualTo(409);
    assertThat(errorCode(other)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(available(wallet)).isEqualByComparingTo("400.00");
  }

  /** Q14 and D04: after 24 h the record counts as absent even before the purge runs. */
  @Test
  void afterTheRetentionPeriodTheBusinessRowStillGuardsTheKey() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000");
    UUID key = UUID.randomUUID();
    MockHttpServletResponse first = withdraw(customer, "200", key);

    mutableClock.advance(Duration.ofHours(24).plusSeconds(1));
    customer = signedInAgain(customer);

    assertThat(body(withdraw(customer, "200.00", key))).isEqualTo(body(first));
    MockHttpServletResponse other = withdraw(customer, "250", key);
    assertThat(errorCode(other)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(available(wallet)).isEqualByComparingTo("800.00");
  }

  // Logs (AC 11) ----------------------------------------------------------------------------------

  @Test
  void logsCarryNoNoteWalletNumberOrIdentity(CapturedOutput output) throws Exception {
    Account customer = withWallet();
    String number = (String) walletOf(customer.id()).get("wallet_number");
    int start = output.getAll().length();

    postMoney(
        DEPOSITS,
        "{\"amount\":100,\"currency\":\"THB\",\"reference\":\"note-7c1e secret text\"}",
        customer.token(),
        UUID.randomUUID());
    withdraw(customer, "5000", UUID.randomUUID());
    withdraw(customer, "50", UUID.randomUUID());

    String logged = output.getAll().substring(start);
    assertThat(logged)
        .doesNotContain("note-7c1e")
        .doesNotContain(number)
        .doesNotContain("Somchai")
        .doesNotContain("Jaidee")
        .doesNotContain("AB1234567");
  }
}
