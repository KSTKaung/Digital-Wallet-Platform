package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * The transfer flow of {@link TransferFlowSupport} with a <b>test risk policy</b> in which the
 * amount chooses the band, so every outcome of {@code POST /transfers} can be reached on purpose
 * (documentations/06 MVP-ADR-24 "Bands"; doc 08 §4C.4 criterion 3). The production policy is tested
 * in the risk module; here only the weights differ, the thresholds are the production ones (40, 70,
 * 90).
 *
 * <p>A fresh sender wallet (younger than 24 hours: +2) sending to a new recipient (+1) scores 3
 * plus the amount band:
 *
 * <pre>
 * amount       weight  score  decision
 * &lt; 1000.00        0      3  ALLOW
 * 1000.00         36     39  ALLOW
 * 1100.00         37     40  MONITOR
 * 1200.00         66     69  MONITOR
 * 1300.00         67     70  MANUAL_REVIEW
 * 1400.00         86     89  MANUAL_REVIEW
 * 1500.00         87     90  BLOCK
 * </pre>
 *
 * The frequency bands start at 1,000 transfers, so they never apply. After a {@code COMPLETED}
 * transfer to the same recipient the new-recipient weight no longer applies (one point less).
 */
@TestPropertySource(
    properties = {
      "app.risk.amount.bands[0].min-amount=1000.00",
      "app.risk.amount.bands[0].weight=36",
      "app.risk.amount.bands[1].min-amount=1100.00",
      "app.risk.amount.bands[1].weight=37",
      "app.risk.amount.bands[2].min-amount=1200.00",
      "app.risk.amount.bands[2].weight=66",
      "app.risk.amount.bands[3].min-amount=1300.00",
      "app.risk.amount.bands[3].weight=67",
      "app.risk.amount.bands[4].min-amount=1400.00",
      "app.risk.amount.bands[4].weight=86",
      "app.risk.amount.bands[5].min-amount=1500.00",
      "app.risk.amount.bands[5].weight=87",
      "app.risk.frequency.bands[0].min-count=1000",
      "app.risk.frequency.bands[0].weight=1",
      "app.risk.frequency.bands[1].min-count=2000",
      "app.risk.frequency.bands[1].weight=2",
      "app.risk.frequency.bands[2].min-count=3000",
      "app.risk.frequency.bands[2].weight=3",
      "app.risk.new-recipient.weight=1",
      "app.risk.new-sender-wallet.bands[0].younger-than=24h",
      "app.risk.new-sender-wallet.bands[0].weight=2",
      "app.risk.new-sender-wallet.bands[1].younger-than=7d",
      "app.risk.new-sender-wallet.bands[1].weight=1"
    })
abstract class TransferReviewFlowSupport extends TransferFlowSupport {

  static final String ALLOW = "999.99";
  static final String HOLD = "1300.00";
  static final String BLOCK = "1500.00";

  /** A held transfer from {@code sender} to {@code recipient}; returns its id. */
  UUID held(Account sender, Account recipient) throws Exception {
    MockHttpServletResponse response = transfer(sender, recipient, HOLD);
    assertThat(response.getStatus()).as("hold").isEqualTo(201);
    assertThat(body(response).get("status").asString()).isEqualTo("PENDING_REVIEW");
    return transferId(response);
  }

  /**
   * Moves a held transfer's deadline into the past, by {@code secondsAgo}, as only the superuser
   * can (the V4 guard keeps both times immutable): test only, as in PR 2's tests.
   */
  void overdue(UUID transferId, long secondsAgo) throws SQLException {
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET held_at = now() - interval '25 hours',"
                    + " review_expires_at = now() - make_interval(secs => ?) WHERE id = ?",
                (double) secondsAgo,
                transferId));
  }
}
