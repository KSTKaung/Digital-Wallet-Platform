package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.wallet.application.LimitProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The {@code VERIFIED} (KYC level {@code FULL}) daily transfer limit of 500,000.00 THB end to end,
 * through HTTP, the risk assessment, the idempotency engine and the money transaction
 * (documentations/06 MVP-ADR-18, -22, -24; doc 08 §4C.4 criterion 2; §5 "Step 7 decisions").
 *
 * <p>The test risk policy of {@link TransferReviewFlowSupport} blocks large amounts, so the
 * sender's usage is seeded on the {@code wallet.daily_limit_usage} row and only small transfers are
 * sent. The seed goes on today's and tomorrow's Asia/Bangkok day, as in {@link
 * TransferConcurrencyIT}, so a test started just before midnight still meets the seeded usage.
 * Usage after a transfer is read on that transfer's own {@code limit_date}. Every assertion reads
 * only this test's own rows.
 */
class TransferVerifiedLimitIT extends TransferReviewFlowSupport {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final String HOLD_ANY_RECIPIENT = "1400.00";

  @Autowired LimitProperties limits;

  /** The tier's limit is the configured, documented value (MVP-ADR-18). */
  @Test
  void theConfiguredVerifiedDailyLimitIs500000AndFullKycGivesAVerifiedWallet() throws Exception {
    assertThat(limits.transfer().daily().verified()).isEqualByComparingTo("500000.00");
    assertThat(limits.transfer().daily().basic()).isEqualByComparingTo("100000.00");

    assertThat(tierOf(withWallet("FULL"))).isEqualTo("VERIFIED");
    assertThat(tierOf(withWallet("BASIC"))).isEqualTo("BASIC");
  }

  /**
   * 499,998.99 used: 1.02 is over by 0.01 and is a stored {@code 409 DAILY_LIMIT_EXCEEDED} (doc 08
   * Q3), 1.01 fills the limit exactly, then even the minimum is refused. The refusal is replayed
   * from its record even once there is room again, and is audited once.
   */
  @Test
  void theVerifiedDailyLimitIsExactToTheCentAndTheRefusalIsStoredAndReplayed() throws Exception {
    Account sender = withWallet("FULL");
    fund(sender, "100.00");
    Account recipient = withWallet();
    seedUsedToday(sender, "499998.99");
    UUID refusedKey = UUID.randomUUID();
    String overByOneCent = transferBody(numberOf(recipient), "1.02", null);

    MockHttpServletResponse refused =
        postTransfer(overByOneCent, sender.token(), refusedKey, randomIp());

    assertCode(refused, 409, "DAILY_LIMIT_EXCEEDED");
    assertThat(transferRecords(sender, refusedKey)).isEqualTo(1);
    // A pre-check refusal: no assessment, no transfer row, nothing held or moved.
    assertThat(assessments(sender)).isZero();
    assertThat(transfersOf(sender)).isEmpty();
    assertBalances(sender, "100.00", "0.00");
    List<Map<String, Object>> audits = transferAudits(sender);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0)).containsEntry("result", "FAILURE");
    assertThat((String) audits.get(0).get("new_value"))
        .contains("\"errorCode\": \"DAILY_LIMIT_EXCEEDED\"");

    // Exactly at the limit: accepted.
    MockHttpServletResponse exact = transfer(sender, recipient, "1.01");
    assertThat(exact.getStatus()).as(exact.getContentAsString()).isEqualTo(201);
    assertThat(body(exact).get("status").asString()).isEqualTo("COMPLETED");
    assertUsage(sender, limitDateOf(transferId(exact)), "500000.00", "0.00");
    assertBalances(sender, "98.99", "0.00");
    assertBalances(recipient, "1.01", "0.00");
    assertBalancedPosting(body(exact).get("reference").asString(), "1.01");

    // Nothing left: the minimum is refused.
    assertCode(transfer(sender, recipient, "1.00"), 409, "DAILY_LIMIT_EXCEEDED");
    assertThat(transfersOf(sender)).hasSize(1);
    assertThat(transferAudits(sender)).hasSize(3);

    // Room again (test only): the stored refusal is still replayed, identical, not audited again,
    // and the same key with another body is a reuse.
    makeRoom(sender, "10.00");
    MockHttpServletResponse replayed =
        postTransfer(overByOneCent, sender.token(), refusedKey, randomIp());

    assertCode(replayed, 409, "DAILY_LIMIT_EXCEEDED");
    assertThat(stable(replayed)).isEqualTo(stable(refused));
    assertThat(transferRecords(sender, refusedKey)).isEqualTo(1);
    assertThat(transferAudits(sender)).hasSize(3);
    assertThat(transfersOf(sender)).hasSize(1);
    assertBalances(sender, "98.99", "0.00");
    assertCode(
        postTransfer(
            transferBody(numberOf(recipient), "1.03", null),
            sender.token(),
            refusedKey,
            randomIp()),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    // A new key with the same body runs fresh and now fits.
    assertThat(
            postTransfer(overByOneCent, sender.token(), UUID.randomUUID(), randomIp()).getStatus())
        .isEqualTo(201);
    assertBalances(sender, "97.97", "0.00");
  }

  /** 499,000.00 used: 999.99 ends one cent below the limit, and the minimum no longer fits. */
  @Test
  void oneCentBelowTheVerifiedLimitIsAcceptedAndTheMinimumThenIsRefused() throws Exception {
    Account sender = withWallet("FULL");
    fund(sender, "2000.00");
    Account recipient = withWallet();
    seedUsedToday(sender, "499000.00");

    MockHttpServletResponse below = transfer(sender, recipient, ALLOW);

    assertThat(below.getStatus()).as(below.getContentAsString()).isEqualTo(201);
    assertThat(body(below).get("status").asString()).isEqualTo("COMPLETED");
    assertUsage(sender, limitDateOf(transferId(below)), "499999.99", "0.00");
    assertCode(transfer(sender, recipient, "1.00"), 409, "DAILY_LIMIT_EXCEEDED");
    assertBalances(sender, "1000.01", "0.00");
    assertBalances(recipient, ALLOW, "0.00");
  }

  /**
   * A held ({@code PENDING_REVIEW}) amount counts as reserved toward the VERIFIED limit: with
   * 498,599.00 used and 1,400.00 held, 1.01 is over by 0.01 (used alone would leave 1,401.00), 1.00
   * fills used + reserved to exactly 500,000.00. Cancelling the hold releases its reserved usage.
   */
  @Test
  void aHeldAmountCountsAsReservedTowardTheVerifiedLimit() throws Exception {
    Account sender = withWallet("FULL");
    fund(sender, "2000.00");
    Account recipient = withWallet();
    seedUsedToday(sender, "498599.00");

    MockHttpServletResponse hold = transfer(sender, recipient, HOLD_ANY_RECIPIENT);
    assertThat(hold.getStatus()).as(hold.getContentAsString()).isEqualTo(201);
    assertThat(body(hold).get("status").asString()).isEqualTo("PENDING_REVIEW");
    UUID holdId = transferId(hold);
    LocalDate day = limitDateOf(holdId);
    assertUsage(sender, day, "498599.00", "1400.00");
    assertBalances(sender, "600.00", "1400.00");

    UUID refusedKey = UUID.randomUUID();
    assertCode(transfer(sender, recipient, "1.01", refusedKey), 409, "DAILY_LIMIT_EXCEEDED");
    assertThat(transferRecords(sender, refusedKey)).isEqualTo(1);
    assertUsage(sender, day, "498599.00", "1400.00");

    MockHttpServletResponse exact = transfer(sender, recipient, "1.00");
    assertThat(exact.getStatus()).as(exact.getContentAsString()).isEqualTo(201);
    assertThat(body(exact).get("status").asString()).isEqualTo("COMPLETED");
    assertUsage(sender, day, "498600.00", "1400.00");
    assertBalances(sender, "599.00", "1400.00");
    assertCode(transfer(sender, recipient, "1.00"), 409, "DAILY_LIMIT_EXCEEDED");

    // The cancel releases the reserved usage, and the room it held is usable again.
    MockHttpServletResponse cancelled = cancel(holdId, sender);
    assertThat(cancelled.getStatus()).isEqualTo(200);
    assertThat(body(cancelled).get("status").asString()).isEqualTo("CANCELLED");
    assertUsage(sender, day, "498600.00", "0.00");
    assertBalances(sender, "1999.00", "0.00");
    assertThat(transfer(sender, recipient, "1.01").getStatus()).isEqualTo(201);
    assertBalances(recipient, "2.01", "0.00");
  }

  /**
   * The same seeded usage and amount: refused for a BASIC wallet, accepted for a VERIFIED one, so
   * the tier is what differs (MVP-ADR-18; Tier.forKycLevel).
   */
  @Test
  void theSameUsageIsRefusedForBasicAndAcceptedForVerified() throws Exception {
    Account recipient = withWallet();
    for (String[] seedAndAmount :
        new String[][] {{"100000.00", "1.00"}, {"499998.99", "1.01"}, {"499000.00", ALLOW}}) {
      String seed = seedAndAmount[0];
      String amount = seedAndAmount[1];
      Account basic = withWallet("BASIC");
      Account verified = withWallet("FULL");
      fund(basic, "1000.00");
      fund(verified, "1000.00");
      seedUsedToday(basic, seed);
      seedUsedToday(verified, seed);

      MockHttpServletResponse basicResponse = transfer(basic, recipient, amount);
      MockHttpServletResponse verifiedResponse = transfer(verified, recipient, amount);

      assertCode(basicResponse, 409, "DAILY_LIMIT_EXCEEDED");
      assertThat(transfersOf(basic)).as("seed " + seed).isEmpty();
      assertBalances(basic, "1000.00", "0.00");
      assertThat(verifiedResponse.getStatus())
          .as("seed " + seed + ": " + verifiedResponse.getContentAsString())
          .isEqualTo(201);
      assertThat(body(verifiedResponse).get("status").asString()).isEqualTo("COMPLETED");
      assertBalances(
          verified,
          new BigDecimal("1000.00").subtract(new BigDecimal(amount)).toPlainString(),
          "0");
    }
  }

  // Helpers -------------------------------------------------------------------------------------

  /**
   * Seeds {@code used} on today's and tomorrow's Bangkok day (test only, as the concurrency IT).
   */
  private void seedUsedToday(Account account, String used) {
    LocalDate today = LocalDate.now(clock.withZone(BANGKOK));
    for (LocalDate day : List.of(today, today.plusDays(1))) {
      jdbc.update(
          "INSERT INTO wallet.daily_limit_usage (wallet_id, operation, limit_date, used)"
              + " VALUES (?, 'TRANSFER', ?, ?)",
          walletId(account),
          day,
          new BigDecimal(used));
    }
  }

  /** Test only: lowers the seeded usage on every day of this wallet, as if a day had passed. */
  private void makeRoom(Account account, String amount) {
    jdbc.update(
        "UPDATE wallet.daily_limit_usage SET used = used - ?, version = version + 1"
            + " WHERE wallet_id = ? AND operation = 'TRANSFER'",
        new BigDecimal(amount),
        walletId(account));
  }

  private String tierOf(Account account) {
    return jdbc.queryForObject(
        "SELECT tier FROM wallet.wallets WHERE customer_id = ?", String.class, account.id());
  }

  private LocalDate limitDateOf(UUID transferId) {
    return jdbc.queryForObject(
        "SELECT limit_date FROM transfer.transfers WHERE id = ?", LocalDate.class, transferId);
  }

  private void assertUsage(Account account, LocalDate day, String used, String reserved) {
    Map<String, Object> usage = transferUsage(account, day).getFirst();
    assertThat((BigDecimal) usage.get("used")).as("used on " + day).isEqualByComparingTo(used);
    assertThat((BigDecimal) usage.get("reserved"))
        .as("reserved on " + day)
        .isEqualByComparingTo(reserved);
  }

  private void assertCode(MockHttpServletResponse response, int status, String code)
      throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    assertThat(errorCode(response)).isEqualTo(code);
  }
}
