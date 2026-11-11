package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/v1/transfers} with the production risk policy (documentations/02 §2.4; doc 06
 * MVP-ADR-03, -06, -18, -20, -21, -24; doc 08 §4C.2 Q3, Q4, Q5, §4C.4 criteria 1, 2, 4, 5, 9, 20;
 * §5 notes). Every refusal is checked to come before the later steps of the Q3 order.
 */
class TransferCreateFlowIT extends TransferFlowSupport {

  private static final LocalDate TODAY_IN_BANGKOK = LocalDate.now(ZoneId.of("Asia/Bangkok"));

  // A completed transfer -------------------------------------------------------------------------

  @Test
  void anAllowedTransferCompletesAtomicallyWithABalancedPosting() throws Exception {
    Account sender = funded("1000.00");
    Account recipient = withWallet();
    String number = numberOf(recipient);
    String ip = randomIp();

    MockHttpServletResponse response =
        postTransfer(
            transferBody(number, "100.5", "rent for may"), sender.token(), UUID.randomUUID(), ip);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = body(response);
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "id",
            "reference",
            "status",
            "direction",
            "amount",
            "currency",
            "counterparty",
            "rejectionReason",
            "reviewExpiresAt",
            "createdAt",
            "ledgerStatus");
    assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(body.get("ledgerStatus").asString()).isEqualTo("POSTED");
    assertThat(body.get("direction").asString()).isEqualTo("SENT");
    assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("100.50");
    assertThat(response.getContentAsString()).contains("\"amount\":100.50");
    assertThat(body.get("currency").asString()).isEqualTo("THB");
    assertThat(body.get("counterparty").get("maskedName").asString()).isEqualTo("Somchai J.");
    assertThat(body.get("counterparty").get("maskedWalletNumber").asString())
        .isEqualTo("*******" + number.substring(7));
    assertThat(body.get("rejectionReason").isNull()).isTrue();
    assertThat(body.get("reviewExpiresAt").isNull()).isTrue();
    // Criterion 20: no description, no score, no rule; no internal ids of the parties.
    assertThat(response.getContentAsString())
        .doesNotContain("rent for may")
        .doesNotContain("score")
        .doesNotContain("NEW_RECIPIENT")
        .doesNotContain(number)
        .doesNotContain(recipient.id().toString())
        .doesNotContain(walletId(recipient).toString());

    UUID id = transferId(response);
    String reference = body.get("reference").asString();
    assertThat(reference).startsWith("TRF-").hasSize(36);
    // Sender debited and receiver credited exactly once; one balanced TRANSFER posting.
    assertBalances(sender, "899.50", "0.00");
    assertBalances(recipient, "100.50", "0.00");
    assertThat(ledgerTransactions(reference)).isEqualTo(1);
    assertBalancedPosting(reference, "100.50");
    assertThat(transferUsage(sender, TODAY_IN_BANGKOK)).hasSize(1);
    assertThat((BigDecimal) transferUsage(sender, TODAY_IN_BANGKOK).get(0).get("used"))
        .isEqualByComparingTo("100.50");

    Map<String, Object> row = transferRow(id);
    assertThat(row)
        .containsEntry("status", "COMPLETED")
        .containsEntry("ledger_status", "POSTED")
        .containsEntry("reference", reference)
        .containsEntry("sender_customer_id", sender.id())
        .containsEntry("receiver_customer_id", recipient.id())
        .containsEntry("sender_wallet_id", walletId(sender))
        .containsEntry("receiver_wallet_id", walletId(recipient))
        .containsEntry("description", "rent for may")
        .containsEntry("limit_date", java.sql.Date.valueOf(TODAY_IN_BANGKOK));
    assertThat(row.get("held_at")).isNull();
    assertThat(row.get("review_expires_at")).isNull();
    // The pre-generated id is the one the assessment was made for (doc 08 Q8).
    assertThat(decisionOf(id)).isEqualTo("ALLOW");
    assertThat(row.get("risk_assessment_id"))
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT id FROM risk.risk_assessments WHERE transfer_id = ?", UUID.class, id));

    // One audit row for the outcome, with the client IP; no description or score.
    List<Map<String, Object>> audits = transferAudits(sender);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0))
        .containsEntry("action", "TRANSFER")
        .containsEntry("result", "SUCCESS")
        .containsEntry("resource_id", id.toString())
        .containsEntry("ip", ip);
    assertThat((String) audits.get(0).get("new_value"))
        .contains("\"status\": \"COMPLETED\"")
        .contains("ledgerTransactionId")
        .doesNotContain("rent for may")
        .doesNotContain("score");
  }

  @Test
  void aMonitoredTransferCompletesLikeAnAllowedOne() throws Exception {
    Account sender = funded("20000.00");
    Account recipient = withWallet();

    // 10,000.01 (+10), new recipient (+15), new wallet (+20): 45, MONITOR (production policy).
    MockHttpServletResponse response = transfer(sender, recipient, "10000.01");

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(body(response).get("status").asString()).isEqualTo("COMPLETED");
    assertThat(decisionOf(transferId(response))).isEqualTo("MONITOR");
    assertBalances(sender, "9999.99", "0.00");
    assertBalances(recipient, "10000.01", "0.00");
  }

  // Q3 step 1: validation ------------------------------------------------------------------------

  @Test
  void validationComesFirstAndUsesNothing() throws Exception {
    Account noWallet = customer();
    Account sender = funded("100.00");
    Account recipient = withWallet();
    String number = numberOf(recipient);
    UUID key = UUID.randomUUID();
    String ip = randomIp();

    // Before KYC: a customer without a wallet still gets the validation error.
    assertValidation(postTransfer(transferBody("123", "1.00", null), noWallet.token(), key, ip));
    assertValidation(postTransfer(transferBody(number, "1.00", null), sender.token(), null, ip));
    assertThat(
            body(postTransfer(transferBody(number, "1.00", null), sender.token(), null, ip))
                .get("message")
                .asString())
        .isEqualTo("Idempotency-Key: header is required");
    MockHttpServletResponse badKey =
        send(
            post(TRANSFERS)
                .contentType(MediaType.APPLICATION_JSON)
                .header(KEY_HEADER, "not-a-uuid")
                .content(transferBody(number, "1.00", null)),
            sender.token());
    assertValidation(badKey);
    assertValidation(
        postTransfer(transferBody("1234567890a", "1.00", null), sender.token(), key, ip));
    assertValidation(
        postTransfer(transferBody("123456789012", "1.00", null), sender.token(), key, ip));
    assertValidation(
        postTransfer(transferBody(number, "1.00", "x".repeat(141)), sender.token(), key, ip));
    assertValidation(
        postTransfer(transferBody(number, "1.00", "tab\\there"), sender.token(), key, ip));
    assertValidation(
        postTransfer(
            "{\"receiverWalletNumber\":\"" + number + "\",\"currency\":\"THB\"}",
            sender.token(),
            key,
            ip));
    MockHttpServletResponse wrongNumber =
        postTransfer(transferBody("123", "1.00", null), sender.token(), key, ip);
    assertThat(body(wrongNumber).get("message").asString())
        .isEqualTo("receiverWalletNumber: must be 11 digits");

    // Step 1, second part: the amount before the currency.
    assertCode(
        postTransfer(
            "{\"receiverWalletNumber\":\"" + number + "\",\"amount\":1.001,\"currency\":\"USD\"}",
            sender.token(),
            key,
            ip),
        400,
        "INVALID_AMOUNT");
    assertCode(
        postTransfer(transferBody(number, "0", null), sender.token(), key, ip),
        400,
        "INVALID_AMOUNT");
    assertCode(
        postTransfer(transferBody(number, "-5.00", null), sender.token(), key, ip),
        400,
        "INVALID_AMOUNT");
    assertCode(
        postTransfer(
            "{\"receiverWalletNumber\":\"" + number + "\",\"amount\":1.00,\"currency\":\"USD\"}",
            sender.token(),
            key,
            ip),
        400,
        "CURRENCY_MISMATCH");
    // An amount with no money (no-wallet customer) is still INVALID_AMOUNT before KYC.
    assertCode(
        postTransfer(transferBody(number, "1.001", null), noWallet.token(), key, ip),
        400,
        "INVALID_AMOUNT");

    // Nothing was counted, stored or assessed, and a 140-character description is fine.
    assertThat(lookupAttempts(sender)).isZero();
    assertThat(lookupAttempts(noWallet)).isZero();
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(assessments(sender)).isZero();
    assertThat(
            postTransfer(transferBody(number, "1.00", "ก".repeat(140)), sender.token(), key, ip)
                .getStatus())
        .isEqualTo(201);
  }

  // Q3 step 2: KYC -------------------------------------------------------------------------------

  @Test
  void kycComesBeforeTheRecipientAndIsNotCounted() throws Exception {
    Account noWallet = customer();
    UUID key = UUID.randomUUID();

    MockHttpServletResponse response =
        postTransfer(
            transferBody(absentNumber(), "10.00", null), noWallet.token(), key, randomIp());

    assertCode(response, 403, "KYC_REQUIRED");
    assertThat(lookupAttempts(noWallet)).isZero();
    assertThat(lookupAudits(noWallet)).isEmpty();
    assertThat(transferRecords(noWallet, key)).isZero();
  }

  // Q3 step 3: the recipient ---------------------------------------------------------------------

  @Test
  void everyIneligibleRecipientGetsTheIdenticalCountedAndAudited404() throws Exception {
    // An unfunded sender: the recipient is refused before the balance (Q3 step 3 before 5).
    Account sender = withWallet();
    Account frozen = withWallet();
    assertThat(freeze(frozen).getStatus()).isEqualTo(200);
    Account closed = withWallet();
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE wallet.wallets SET status = 'CLOSED' WHERE customer_id = ?",
                closed.id()));
    Account active = withWallet();
    UUID key = UUID.randomUUID();
    String ip = randomIp();

    List<String> numbers =
        List.of(
            absentNumber(), wrongCheckDigit(numberOf(active)), numberOf(frozen), numberOf(closed));
    List<String> bodies = new java.util.ArrayList<>();
    for (String number : numbers) {
      MockHttpServletResponse response =
          postTransfer(transferBody(number, "10.00", null), sender.token(), key, ip);
      assertCode(response, 404, "NOT_FOUND");
      bodies.add(stable(response));
    }
    assertThat(bodies).containsOnly(bodies.get(0));

    // Counted and audited like a lookup (Q5), never naming the case; the key is not used.
    assertThat(lookupAttempts(sender)).isEqualTo(4);
    List<Map<String, Object>> audits = lookupAudits(sender);
    assertThat(audits).hasSize(4);
    assertThat(audits)
        .allSatisfy(
            audit -> {
              assertThat(audit).containsEntry("result", "FAILURE").containsEntry("ip", ip);
              assertThat(audit.get("resource_id")).isNull();
              assertThat((String) audit.get("new_value")).isEqualTo("{\"outcome\": \"NOT_FOUND\"}");
            });
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(assessments(sender)).isZero();
    assertThat(transfersOf(sender)).isEmpty();
  }

  @Test
  void theSendersOwnWalletIsSelfTransferAfterTheNotFoundCases() throws Exception {
    Account sender = funded("100.00");
    UUID key = UUID.randomUUID();

    MockHttpServletResponse response =
        postTransfer(
            transferBody(numberOf(sender), "10.00", null), sender.token(), key, randomIp());

    assertCode(response, 409, "SELF_TRANSFER");
    // Found, so not a lookup attempt (Q5): no attempt row, no WALLET_LOOKUP audit row.
    assertThat(lookupAttempts(sender)).isZero();
    assertThat(lookupAudits(sender)).isEmpty();
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(assessments(sender)).isZero();
    assertBalances(sender, "100.00", "0.00");
  }

  /**
   * The sender's own wallet, when frozen, is one of the 404 cases, which come before {@code
   * SELF_TRANSFER} (Q3 step 3): the identical 404, counted and audited as not found.
   */
  @Test
  void theSendersOwnFrozenWalletIsTheCountedIdentical404() throws Exception {
    Account sender = funded("100.00");
    assertThat(freeze(sender).getStatus()).isEqualTo(200);
    UUID key = UUID.randomUUID();
    MockHttpServletResponse missing =
        postTransfer(transferBody(absentNumber(), "10.00", null), sender.token(), key, randomIp());

    MockHttpServletResponse own =
        postTransfer(
            transferBody(numberOf(sender), "10.00", null), sender.token(), key, randomIp());

    assertCode(own, 404, "NOT_FOUND");
    assertThat(stable(own)).isEqualTo(stable(missing));
    assertThat(lookupAttempts(sender)).isEqualTo(2);
    assertThat(lookupAudits(sender))
        .hasSize(2)
        .allSatisfy(
            audit ->
                assertThat((String) audit.get("new_value"))
                    .isEqualTo("{\"outcome\": \"NOT_FOUND\"}"));
    assertThat(transferRecords(sender, key)).isZero();
  }

  /**
   * Path variants of {@code POST /transfers} (Q15 limiter): the trailing slash and an encoded slash
   * are never served. A percent-encoded unreserved character ({@code %74} = {@code t}) is decoded
   * by MVC routing and served as an ordinary transfer; the money limiter decodes it alike and
   * counts it ({@code MoneyRateLimitWebTest.pathVariantsOfTransfersCannotBypassTheMoneyCount}).
   */
  @Test
  void pathVariantsOfTheTransferEndpointAreNotServedOrAreOrdinaryTransfers() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    String json = transferBody(numberOf(recipient), "10.00", null);

    for (String path : List.of(TRANSFERS + "/", "/api/v1/transfers%2F")) {
      assertThat(postRaw(path, json, sender).getStatus()).as(path).isIn(400, 404, 405);
    }
    assertThat(transfersOf(sender)).isEmpty();
    assertBalances(sender, "100.00", "0.00");

    assertThat(postRaw("/api/v1/%74ransfers", json, sender).getStatus()).isEqualTo(201);
    assertThat(transfersOf(sender)).hasSize(1);
  }

  private MockHttpServletResponse postRaw(String path, String json, Account sender)
      throws Exception {
    return send(
        post(java.net.URI.create(path))
            .contentType(MediaType.APPLICATION_JSON)
            .header(KEY_HEADER, UUID.randomUUID().toString())
            .content(json),
        sender.token());
  }

  @Test
  void aFoundRecipientIsNeitherCountedNorAuditedAsALookup() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    String ip = randomIp();

    MockHttpServletResponse response =
        postTransfer(
            transferBody(numberOf(recipient), "10.00", null),
            sender.token(),
            UUID.randomUUID(),
            ip);

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(lookupAttempts(sender)).isZero();
    assertThat(lookupAudits(sender)).isEmpty();
    // The transfer's own audit row covers the request.
    assertThat(transferAudits(sender)).hasSize(1);
  }

  // Q3 step 4: the early replay ------------------------------------------------------------------

  @Test
  void aCompletedTransferIsReplayedBeforeThePrecheckAndADifferentBodyIs409() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    String json = transferBody(numberOf(recipient), "100.00", "rent");

    MockHttpServletResponse first = postTransfer(json, sender.token(), key, randomIp());
    assertThat(first.getStatus()).isEqualTo(201);
    // The balance is now 0: a fresh run would be refused by the pre-check.
    MockHttpServletResponse replay = postTransfer(json, sender.token(), key, randomIp());

    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(body(replay)).isEqualTo(body(first));
    assertThat(assessments(sender)).isEqualTo(1);
    assertThat(transfersOf(sender)).hasSize(1);
    assertBalances(sender, "0.00", "0.00");
    assertBalances(recipient, "100.00", "0.00");
    // A formatting difference is the same request (canonical hash).
    assertThat(
            postTransfer(
                    "{\"currency\":\"THB\",\"amount\":100.0000,\"description\":\"rent\","
                        + "\"receiverWalletNumber\":\""
                        + numberOf(recipient)
                        + "\"}",
                    sender.token(),
                    key,
                    randomIp())
                .getStatus())
        .isEqualTo(201);

    for (String other :
        List.of(
            transferBody(numberOf(recipient), "99.99", "rent"),
            transferBody(numberOf(recipient), "100.00", "other"),
            transferBody(numberOf(recipient), "100.00", null))) {
      assertCode(
          postTransfer(other, sender.token(), key, randomIp()), 409, "IDEMPOTENCY_KEY_REUSED");
    }
    assertThat(assessments(sender)).isEqualTo(1);
    assertThat(transferAudits(sender)).hasSize(1);
  }

  @Test
  void anotherCustomersIdenticalKeyRunsFresh() throws Exception {
    Account first = funded("100.00");
    Account second = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();

    MockHttpServletResponse a = transfer(first, recipient, "10.00", key);
    MockHttpServletResponse b = transfer(second, recipient, "10.00", key);

    assertThat(a.getStatus()).isEqualTo(201);
    assertThat(b.getStatus()).isEqualTo(201);
    assertThat(transferId(a)).isNotEqualTo(transferId(b));
    assertBalances(recipient, "20.00", "0.00");
  }

  // Q3 step 5: the pre-check ---------------------------------------------------------------------

  @Test
  void aPrecheckRefusalIsStoredAuditedOnceAndReplayedAfterADeposit() throws Exception {
    Account sender = withWallet();
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    String json = transferBody(numberOf(recipient), "100.00", null);

    MockHttpServletResponse refused = postTransfer(json, sender.token(), key, randomIp());

    assertCode(refused, 409, "INSUFFICIENT_BALANCE");
    assertThat(body(refused).get("message").asString())
        .isEqualTo("Available balance is lower than the requested amount");
    assertThat(transferRecords(sender, key)).isEqualTo(1);
    // The pre-check keeps an obvious refusal from creating an assessment (Q3).
    assertThat(assessments(sender)).isZero();
    assertThat(transfersOf(sender)).isEmpty();
    List<Map<String, Object>> audits = transferAudits(sender);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0)).containsEntry("result", "FAILURE");
    assertThat((String) audits.get(0).get("new_value"))
        .contains("\"errorCode\": \"INSUFFICIENT_BALANCE\"");

    fund(sender, "1000.00");
    MockHttpServletResponse replayed = postTransfer(json, sender.token(), key, randomIp());

    assertCode(replayed, 409, "INSUFFICIENT_BALANCE");
    assertThat(stable(replayed)).isEqualTo(stable(refused));
    assertThat(transferAudits(sender)).hasSize(1);
    assertThat(assessments(sender)).isZero();
    assertBalances(sender, "1000.00", "0.00");
    // A new key runs fresh.
    assertThat(postTransfer(json, sender.token(), UUID.randomUUID(), randomIp()).getStatus())
        .isEqualTo(201);
  }

  @Test
  void aFrozenSenderIsAStoredWalletFrozenAfterTheRecipient() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    assertThat(freeze(sender).getStatus()).isEqualTo(200);
    UUID key = UUID.randomUUID();

    // The recipient comes first: a missing one is the 404, not WALLET_FROZEN.
    assertCode(
        postTransfer(transferBody(absentNumber(), "10.00", null), sender.token(), key, randomIp()),
        404,
        "NOT_FOUND");
    MockHttpServletResponse frozen = transfer(sender, recipient, "10.00", key);

    assertCode(frozen, 409, "WALLET_FROZEN");
    assertThat(transferRecords(sender, key)).isEqualTo(1);
    assertCode(transfer(sender, recipient, "10.00", key), 409, "WALLET_FROZEN");
    assertThat(transferAudits(sender)).hasSize(1);
    assertBalances(sender, "100.00", "0.00");
  }

  @Test
  void theMinimumIsNotStoredAndTheKeyStaysUsable() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();

    MockHttpServletResponse below = transfer(sender, recipient, "0.99", key);

    assertCode(below, 400, "INVALID_AMOUNT");
    assertThat(body(below).get("message").asString()).isEqualTo("amount: is below the minimum");
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(transferAudits(sender)).isEmpty();
    // 1.00 is the minimum: accepted with the same key.
    MockHttpServletResponse atMinimum = transfer(sender, recipient, "1.00", key);
    assertThat(atMinimum.getStatus()).isEqualTo(201);
    assertBalances(sender, "99.00", "0.00");
  }

  // Limits ±0.01 (criterion 2)
  // ---------------------------------------------------------------------

  @Test
  void theSingleLimitIs50000AndIsStored() throws Exception {
    Account sender = funded("50000.01");
    Account recipient = withWallet();
    UUID refusedKey = UUID.randomUUID();

    assertCode(transfer(sender, recipient, "50000.01", refusedKey), 409, "SINGLE_LIMIT_EXCEEDED");
    assertThat(transferRecords(sender, refusedKey)).isEqualTo(1);
    MockHttpServletResponse atLimit = transfer(sender, recipient, "50000.00");

    assertThat(atLimit.getStatus()).isEqualTo(201);
    assertBalances(sender, "0.01", "0.00");
  }

  /** BASIC: 100,000.00 per Asia/Bangkok day, used + reserved (MVP-ADR-18, -22). */
  @Test
  void theBasicDailyLimitIsExactToTheCent() throws Exception {
    Account sender = funded("100100.00");
    Account recipient = withWallet();

    assertThat(transfer(sender, recipient, "50000.00").getStatus()).isEqualTo(201);
    assertThat(transfer(sender, recipient, "49999.00").getStatus()).isEqualTo(201);
    // 99,999.00 used: 1.01 is over by 0.01, 1.00 fills the limit exactly.
    assertCode(transfer(sender, recipient, "1.01"), 409, "DAILY_LIMIT_EXCEEDED");
    assertThat(transfer(sender, recipient, "1.00").getStatus()).isEqualTo(201);

    assertThat(transferUsed(sender)).isEqualByComparingTo("100000.00");
    assertBalances(sender, "100.00", "0.00");
  }

  @Test
  void theBalanceBoundaryIsExactToTheCent() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();

    assertCode(transfer(sender, recipient, "100.01"), 409, "INSUFFICIENT_BALANCE");
    assertThat(transfer(sender, recipient, "100.00").getStatus()).isEqualTo(201);
    assertBalances(sender, "0.00", "0.00");
  }

  // Criterion 4: risk failure --------------------------------------------------------------------

  @Test
  void aRiskFailureIs500WithNoRowNoHoldNothingStoredAndAnAuditRow() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    doThrow(new DataIntegrityViolationException("injected"))
        .when(riskRowsSpy)
        .lockSender(sender.id());

    MockHttpServletResponse failed = transfer(sender, recipient, "10.00", key);

    assertCode(failed, 500, "SYSTEM_ERROR");
    assertThat(body(failed).get("message").asString()).isEqualTo("An unexpected error occurred");
    assertThat(transfersOf(sender)).isEmpty();
    assertThat(assessments(sender)).isZero();
    assertThat(transferRecords(sender, key)).isZero();
    assertBalances(sender, "100.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    List<Map<String, Object>> audits = transferAudits(sender);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0)).containsEntry("result", "FAILURE");
    assertThat((String) audits.get(0).get("new_value")).contains("\"errorCode\": \"SYSTEM_ERROR\"");

    // Not stored: the same key runs fresh once the assessment works again.
    org.mockito.Mockito.reset(riskRowsSpy);
    assertThat(transfer(sender, recipient, "10.00", key).getStatus()).isEqualTo(201);
    assertBalances(sender, "90.00", "0.00");
  }

  // Criterion 5: a forced failure leaves nothing -------------------------------------------------

  @Test
  void aFailureAfterThePostingRollsEverythingBack() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    doThrow(new DataIntegrityViolationException("injected"))
        .when(transferRowsSpy)
        .insert(any(), anyLong());
    int ledgerBefore = senderLedgerTransactions(sender);

    MockHttpServletResponse failed = transfer(sender, recipient, "10.00", key);

    assertCode(failed, 500, "SYSTEM_ERROR");
    assertBalances(sender, "100.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(senderLedgerTransactions(sender)).isEqualTo(ledgerBefore);
    assertThat(transfersOf(sender)).isEmpty();
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(transferAudits(sender)).isEmpty();
    assertThat(transferUsed(sender)).isEqualByComparingTo("0");
    // The assessment stays (Q4), and the same key runs fresh.
    assertThat(assessments(sender)).isEqualTo(1);
    org.mockito.Mockito.reset(transferRowsSpy);
    assertThat(transfer(sender, recipient, "10.00", key).getStatus()).isEqualTo(201);
    assertBalances(sender, "90.00", "0.00");
    assertBalances(recipient, "10.00", "0.00");
  }

  @Test
  void aRecipientFrozenAfterResolutionIsTheIdentical404UnderTheLock() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    // Freeze the recipient between the resolution and the money transaction: when the transfer
    // module asks whether a completed transfer exists (the last step before the assessment).
    org.mockito.Mockito.doAnswer(
            invocation -> {
              withTriggersDisabled(
                  superuser ->
                      update(
                          superuser,
                          "UPDATE wallet.wallets SET status = 'FROZEN' WHERE customer_id = ?",
                          recipient.id()));
              return invocation.callRealMethod();
            })
        .when(transferRowsSpy)
        .completedTransferExists(sender.id(), recipient.id());

    MockHttpServletResponse response = transfer(sender, recipient, "10.00", key);

    assertCode(response, 404, "NOT_FOUND");
    // Found at the resolution, so not counted there, and not counted under the lock either
    // (owner, 2026-11-09); nothing stored or moved.
    assertThat(lookupAttempts(sender)).isZero();
    assertThat(lookupAudits(sender)).isEmpty();
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(transfersOf(sender)).isEmpty();
    assertBalances(sender, "100.00", "0.00");
  }

  // Criterion 9: concurrent duplicates
  // -------------------------------------------------------------

  @Test
  void concurrentDuplicatesCreateOneTransfer() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    String json = transferBody(numberOf(recipient), "60.00", null);
    int requests = 6;
    CyclicBarrier start = new CyclicBarrier(requests);
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    try {
      List<Future<MockHttpServletResponse>> results = new java.util.ArrayList<>();
      for (int i = 0; i < requests; i++) {
        Callable<MockHttpServletResponse> call =
            () -> {
              start.await(10, TimeUnit.SECONDS);
              return postTransfer(json, sender.token(), key, randomIp());
            };
        results.add(pool.submit(call));
      }
      List<String> bodies = new java.util.ArrayList<>();
      for (Future<MockHttpServletResponse> result : results) {
        MockHttpServletResponse response = result.get(60, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(201);
        bodies.add(response.getContentAsString());
      }
      assertThat(bodies.stream().map(this::idOf).distinct()).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(transfersOf(sender)).hasSize(1);
    assertBalances(sender, "40.00", "0.00");
    assertBalances(recipient, "60.00", "0.00");
    assertThat(transferAudits(sender)).hasSize(1);
    // Assessments that lost to the same key stay (Q4).
    assertThat(assessments(sender)).isBetween(1, requests);
  }

  // Description length in code points (owner, 2026-11-11) ---------------------------------------

  /**
   * 140 code points outside the BMP (280 UTF-16 units) are accepted and stored as PostgreSQL {@code
   * varchar(140)} counts them; 141 is the validation error naming the field, never the value.
   */
  @Test
  void aDescriptionOf140NonBmpCharactersIsStoredAnd141IsRefused() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    String number = numberOf(recipient);
    String grin = CreateTransferRequestTest.GRIN;

    MockHttpServletResponse accepted =
        postTransfer(
            transferBody(number, "10.00", grin.repeat(140)),
            sender.token(),
            UUID.randomUUID(),
            randomIp());

    assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(201);
    assertStoredDescription(transferId(accepted), grin.repeat(140), 140);

    UUID key = UUID.randomUUID();
    MockHttpServletResponse refused =
        postTransfer(
            transferBody(number, "10.00", grin.repeat(141)), sender.token(), key, randomIp());

    assertValidationNamingOnlyTheField(
        refused, "description: must be at most 140 characters", grin);
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(transfersOf(sender)).hasSize(1);
    assertBalances(sender, "90.00", "0.00");
  }

  /**
   * A flag is one grapheme but two code points (regional indicators, category So): 70 flags are 140
   * and accepted, 70 flags and one letter are 141 and refused.
   */
  @Test
  void aMultiCodePointEmojiCountsAsItsCodePoints() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    String number = numberOf(recipient);
    String flag = CreateTransferRequestTest.THAI_FLAG;

    MockHttpServletResponse accepted =
        postTransfer(
            transferBody(number, "10.00", flag.repeat(70)),
            sender.token(),
            UUID.randomUUID(),
            randomIp());

    assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(201);
    assertStoredDescription(transferId(accepted), flag.repeat(70), 140);

    UUID key = UUID.randomUUID();
    MockHttpServletResponse refused =
        postTransfer(
            transferBody(number, "10.00", flag.repeat(70) + "a"), sender.token(), key, randomIp());

    assertValidationNamingOnlyTheField(
        refused, "description: must be at most 140 characters", flag);
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(transfersOf(sender)).hasSize(1);
  }

  /**
   * A ZWJ sequence holds U+200D (category Cf): refused by the format-character rule, not length.
   */
  @Test
  void aZwjSequenceIsRefusedAsAFormatCharacter() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();

    MockHttpServletResponse refused =
        postTransfer(
            transferBody(numberOf(recipient), "10.00", CreateTransferRequestTest.ZWJ_FAMILY),
            sender.token(),
            key,
            randomIp());

    assertValidationNamingOnlyTheField(
        refused,
        "description: must not contain control or format characters",
        CreateTransferRequestTest.ZWJ_FAMILY);
    assertThat(transferRecords(sender, key)).isZero();
    assertThat(transfersOf(sender)).isEmpty();
    assertThat(assessments(sender)).isZero();
  }

  // Description in the request hash (owner, 2026-11-11) -----------------------------------------

  /** Omitted and explicit {@code null} are the same request, in either order. */
  @Test
  void anOmittedAndANullDescriptionAreTheSameRequest() throws Exception {
    for (boolean nullFirst : List.of(false, true)) {
      Account sender = funded("100.00");
      Account recipient = withWallet();
      UUID key = UUID.randomUUID();
      String omitted = transferBody(numberOf(recipient), "10.00", null);
      String explicitNull = omitted.substring(0, omitted.length() - 1) + ",\"description\":null}";

      MockHttpServletResponse first =
          postTransfer(nullFirst ? explicitNull : omitted, sender.token(), key, randomIp());
      MockHttpServletResponse replay =
          postTransfer(nullFirst ? omitted : explicitNull, sender.token(), key, randomIp());

      assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
      assertThat(replay.getStatus()).as(replay.getContentAsString()).isEqualTo(201);
      assertThat(body(replay)).isEqualTo(body(first));
      assertThat(transferRow(transferId(first)).get("description")).isNull();
      assertThat(transfersOf(sender)).hasSize(1);
      assertThat(assessments(sender)).isEqualTo(1);
      assertThat(transferAudits(sender)).hasSize(1);
      assertBalances(sender, "90.00", "0.00");
      assertBalances(recipient, "10.00", "0.00");
    }
  }

  /**
   * {@code ""} is accepted as a first request and is a different request from an omitted or {@code
   * null} description, in both directions: the same key is {@code 409 IDEMPOTENCY_KEY_REUSED},
   * which writes nothing.
   */
  @Test
  void anEmptyDescriptionIsAcceptedAndDiffersFromNone() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    String number = numberOf(recipient);
    String omitted = transferBody(number, "10.00", null);
    String explicitNull = omitted.substring(0, omitted.length() - 1) + ",\"description\":null}";
    String empty = transferBody(number, "10.00", "");

    UUID emptyKey = UUID.randomUUID();
    MockHttpServletResponse first = postTransfer(empty, sender.token(), emptyKey, randomIp());
    assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
    // Hashed as the value it stores (TransferRequestFingerprint): "" is kept, not made null.
    assertThat(transferRow(transferId(first)).get("description")).isEqualTo("");
    for (String other : List.of(omitted, explicitNull)) {
      assertCode(
          postTransfer(other, sender.token(), emptyKey, randomIp()), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    UUID noneKey = UUID.randomUUID();
    assertThat(postTransfer(omitted, sender.token(), noneKey, randomIp()).getStatus())
        .isEqualTo(201);
    assertCode(
        postTransfer(empty, sender.token(), noneKey, randomIp()), 409, "IDEMPOTENCY_KEY_REUSED");

    assertThat(transfersOf(sender)).hasSize(2);
    assertThat(assessments(sender)).isEqualTo(2);
    assertThat(transferAudits(sender)).hasSize(2);
    assertBalances(sender, "80.00", "0.00");
  }

  /** Leading and trailing spaces are part of the value: stored untrimmed, and hashed as sent. */
  @Test
  void aSpacedDescriptionIsStoredUntrimmedAndDiffersFromTheTrimmedOne() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    String number = numberOf(recipient);
    UUID key = UUID.randomUUID();

    MockHttpServletResponse spaced =
        postTransfer(transferBody(number, "10.00", " rent "), sender.token(), key, randomIp());

    assertThat(spaced.getStatus()).as(spaced.getContentAsString()).isEqualTo(201);
    assertThat(transferRow(transferId(spaced)).get("description")).isEqualTo(" rent ");
    assertCode(
        postTransfer(transferBody(number, "10.00", "rent"), sender.token(), key, randomIp()),
        409,
        "IDEMPOTENCY_KEY_REUSED");

    UUID trimmedKey = UUID.randomUUID();
    MockHttpServletResponse trimmed =
        postTransfer(transferBody(number, "10.00", "rent"), sender.token(), trimmedKey, randomIp());
    assertThat(trimmed.getStatus()).isEqualTo(201);
    assertThat(transferRow(transferId(trimmed)).get("description")).isEqualTo("rent");
    assertCode(
        postTransfer(
            transferBody(number, "10.00", " rent "), sender.token(), trimmedKey, randomIp()),
        409,
        "IDEMPOTENCY_KEY_REUSED");

    assertThat(transfersOf(sender)).hasSize(2);
    assertThat(assessments(sender)).isEqualTo(2);
    assertThat(transferAudits(sender)).hasSize(2);
    assertBalances(sender, "80.00", "0.00");
  }

  // Helpers --------------------------------------------------------------------------------------

  /** The stored value is the one sent, and PostgreSQL counts {@code codePoints} characters. */
  private void assertStoredDescription(UUID transferId, String expected, int codePoints) {
    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT description, char_length(description) AS length"
                + " FROM transfer.transfers WHERE id = ?",
            transferId);
    assertThat(stored.get("description")).isEqualTo(expected);
    assertThat(((Number) stored.get("length")).intValue()).isEqualTo(codePoints);
    assertThat(expected.codePointCount(0, expected.length())).isEqualTo(codePoints);
  }

  /** {@code 400 VALIDATION_ERROR} with exactly {@code message}; the value appears nowhere. */
  private void assertValidationNamingOnlyTheField(
      MockHttpServletResponse response, String message, String valuePart) throws Exception {
    assertValidation(response);
    assertThat(body(response).get("message").asString()).isEqualTo(message);
    String raw = response.getContentAsString();
    assertThat(raw).doesNotContain(valuePart);
    StringBuilder escaped = new StringBuilder();
    valuePart.chars().forEach(c -> escaped.append(String.format("\\u%04x", c)));
    assertThat(raw.toLowerCase(java.util.Locale.ROOT)).doesNotContain(escaped.toString());
  }

  private String idOf(String json) {
    return jsonMapper.readTree(json).get("id").asString();
  }

  private int senderLedgerTransactions(Account account) {
    return jdbc.queryForObject(
        """
        SELECT count(DISTINCT e.transaction_id)
        FROM ledger.ledger_entries e JOIN ledger.ledger_accounts a ON a.id = e.account_id
        WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER'
        """,
        Integer.class,
        walletId(account));
  }

  private void assertValidation(MockHttpServletResponse response) throws Exception {
    assertCode(response, 400, "VALIDATION_ERROR");
  }

  private void assertCode(MockHttpServletResponse response, int status, String code)
      throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    assertThat(errorCode(response)).isEqualTo(code);
  }
}
