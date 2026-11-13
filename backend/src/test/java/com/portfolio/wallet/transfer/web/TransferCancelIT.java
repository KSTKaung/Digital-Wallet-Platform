package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.transfer.application.TransferCancellationService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/v1/transfers/{id}/cancel} (documentations/02 §2.4; doc 06 MVP-ADR-19, -22, -24
 * "Cancel", "Review outcomes" cases 4 and 7; doc 08 §4C.3, §4C.4 criteria 7, 11, 14a; §5 notes):
 * only the sender, only while held and before the deadline, also with a frozen sender wallet; the
 * hold is released exactly once; after the deadline the cancel expires the transfer and answers
 * {@code 409}; the deadline crossing between the check and the UPDATE is {@code 409}, never {@code
 * 500}.
 */
class TransferCancelIT extends TransferReviewFlowSupport {

  @Autowired TransferCancellationService cancellations;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void theSenderCancelsAHeldTransferAndTheHoldIsReleased() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    assertBalances(sender, "700.00", "1300.00");

    MockHttpServletResponse cancelled = cancel(id, sender);

    assertThat(cancelled.getStatus()).isEqualTo(200);
    JsonNode body = body(cancelled);
    assertThat(body.get("id").asString()).isEqualTo(id.toString());
    assertThat(body.get("status").asString()).isEqualTo("CANCELLED");
    assertThat(body.get("reviewExpiresAt").isNull()).isTrue();
    assertThat(body.get("counterparty").get("maskedName").asString()).isEqualTo("Somchai J.");
    assertThat(cancelled.getContentAsString()).doesNotContain("rent");
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    assertThat(transferUsed(sender)).isEqualByComparingTo("0");
    Map<String, Object> row = transferRow(id);
    assertThat(row).containsEntry("status", "CANCELLED");
    assertThat(row.get("decided_at")).isNotNull();
    // Review case 4, and one audit row with the same facts.
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "CANCEL")
                    .containsEntry("decided_by", "CUSTOMER")
                    .containsEntry("requested_action", "CANCEL")
                    .containsEntry("actor_user_id", sender.id())
                    .containsEntry("system_reason", null)
                    .containsEntry("reason_category", null)
                    .containsEntry("reason", null));
    List<Map<String, Object>> audits = reviewAudits(sender, id);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0)).containsEntry("actor_type", "CUSTOMER");
    assertThat((String) audits.get(0).get("new_value"))
        .contains("\"action\": \"CANCEL\"")
        .contains("\"decidedBy\": \"CUSTOMER\"")
        .contains("\"requestedAction\": \"CANCEL\"")
        .contains("\"status\": \"CANCELLED\"")
        .doesNotContain("systemReason");

    // A second cancel finds it no longer held: 409, and nothing more is written.
    MockHttpServletResponse again = cancel(id, sender);
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(errorCode(again)).isEqualTo("INVALID_TRANSFER_STATE");
    assertThat(reviewRows(id)).hasSize(1);
    assertThat(reviewAudits(sender, id)).hasSize(1);
    assertBalances(sender, "2000.00", "0.00");
  }

  @Test
  void anyoneButTheSenderGetsTheIdentical404() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account stranger = withWallet();
    UUID id = held(sender, recipient);

    MockHttpServletResponse missing = cancel(UUID.randomUUID(), sender);
    MockHttpServletResponse byRecipient = cancel(id, recipient);
    MockHttpServletResponse byStranger = cancel(id, stranger);

    for (MockHttpServletResponse response : List.of(missing, byRecipient, byStranger)) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(stable(response)).isEqualTo(stable(missing));
    }
    assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");
    assertThat(reviewRows(id)).isEmpty();
  }

  @Test
  void aTransferThatIsNotHeldIs409WithoutAReviewRow() throws Exception {
    Account sender = funded("3000.00");
    Account recipient = withWallet();
    UUID completed = transferId(transfer(sender, recipient, ALLOW));
    assertThat(transfer(sender, withWallet(), BLOCK).getStatus()).isEqualTo(403);
    UUID blocked =
        (UUID)
            transfersOf(sender).stream()
                .filter(row -> "BLOCKED".equals(row.get("status")))
                .findFirst()
                .orElseThrow()
                .get("id");

    for (UUID id : List.of(completed, blocked)) {
      MockHttpServletResponse response = cancel(id, sender);
      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(errorCode(response)).isEqualTo("INVALID_TRANSFER_STATE");
      assertThat(reviewRows(id)).isEmpty();
    }
    assertThat(transferRow(completed)).containsEntry("status", "COMPLETED");
    assertThat(transferRow(blocked)).containsEntry("status", "BLOCKED");
  }

  @Test
  void aFrozenSenderCanStillCancel() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    assertThat(freeze(sender).getStatus()).isEqualTo(200);

    MockHttpServletResponse cancelled = cancel(id, sender);

    assertThat(cancelled.getStatus()).isEqualTo(200);
    assertThat(body(cancelled).get("status").asString()).isEqualTo("CANCELLED");
    assertBalances(sender, "2000.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
  }

  @Test
  void aCancelAfterTheDeadlineExpiresTheTransferReleasesTheHoldAndIs409() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);

    MockHttpServletResponse late = cancel(id, sender);

    assertThat(late.getStatus()).isEqualTo(409);
    assertThat(errorCode(late)).isEqualTo("INVALID_TRANSFER_STATE");
    Map<String, Object> row = transferRow(id);
    assertThat(row).containsEntry("status", "EXPIRED");
    assertThat(row.get("decided_at")).isNotNull();
    // Neither wallet's total changes; the hold and the limit are released on the stored day.
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    // Review case 7.
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "EXPIRE")
                    .containsEntry("decided_by", "SYSTEM")
                    .containsEntry("requested_action", "CANCEL")
                    .containsEntry("actor_user_id", sender.id())
                    .containsEntry("system_reason", "REVIEW_DEADLINE_PASSED"));
    List<Map<String, Object>> audits = reviewAudits(sender, id);
    assertThat(audits).hasSize(1);
    assertThat((String) audits.get(0).get("new_value"))
        .contains("\"action\": \"EXPIRE\"")
        .contains("\"decidedBy\": \"SYSTEM\"")
        .contains("\"systemReason\": \"REVIEW_DEADLINE_PASSED\"")
        .contains("\"status\": \"EXPIRED\"");
    // Final: another cancel is 409 and writes nothing.
    assertThat(cancel(id, sender).getStatus()).isEqualTo(409);
    assertThat(reviewRows(id)).hasSize(1);
  }

  /**
   * The deadline passes between the service's check and the status UPDATE: the V4 guard raises
   * {@code restrict_violation}, which is {@code 409}, never {@code 500}; everything rolls back, the
   * hold stays for the expiry, and the next action expires it (doc 08 §5, V11 review).
   *
   * <p>Deterministic, with no timing margin: the deadline is already in the past, and the service's
   * own check is made to see a time one hour before it, exactly as if the deadline had passed after
   * that check. (Moving the deadline from inside the transaction is impossible: the cancel holds
   * the transfer row lock, so any other connection's UPDATE of it would wait on this request.)
   */
  @Test
  void theDeadlineCrossedBeforeTheUpdateIs409AndRollsBack() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    doAnswer(invocation -> ((Instant) invocation.callRealMethod()).minus(Duration.ofHours(1)))
        .when(transferRowsSpy)
        .databaseNow();

    MockHttpServletResponse crossed = cancel(id, sender);

    assertThat(crossed.getStatus()).as(crossed.getContentAsString()).isEqualTo(409);
    assertThat(errorCode(crossed)).isEqualTo("INVALID_TRANSFER_STATE");
    assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("1300.00");
    assertThat(reviewRows(id)).isEmpty();
    assertThat(reviewAudits(sender, id)).isEmpty();

    org.mockito.Mockito.reset(transferRowsSpy);
    assertThat(cancel(id, sender).getStatus()).isEqualTo(409);
    assertThat(transferRow(id)).containsEntry("status", "EXPIRED");
    assertBalances(sender, "2000.00", "0.00");
    assertThat(reviewRows(id)).hasSize(1);
  }

  /** A late cancel expires and releases the hold in any sender wallet status (MVP-ADR-24). */
  @ParameterizedTest
  @ValueSource(strings = {"FROZEN", "CLOSED"})
  void aLateCancelReleasesTheHoldWhateverTheSenderWalletStatus(String status) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE wallet.wallets SET status = ? WHERE customer_id = ?",
                status,
                sender.id()));
    overdue(id, 60);

    MockHttpServletResponse late = cancel(id, sender);

    assertThat(late.getStatus()).isEqualTo(409);
    assertThat(errorCode(late)).isEqualTo("INVALID_TRANSFER_STATE");
    assertThat(transferRow(id)).containsEntry("status", "EXPIRED");
    assertBalances(sender, "2000.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(review -> assertThat(review).containsEntry("action", "EXPIRE"));
  }

  /** M2: a cancel inside a caller's transaction is refused before it does anything. */
  @Test
  void aCancelInsideAnotherTransactionIsRefusedAndChangesNothing() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    TransactionTemplate outer = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () -> outer.executeWithoutResult(status -> cancellations.cancel(sender.id(), id, null)))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");
    assertThat(reviewRows(id)).isEmpty();
  }

  /** Hold invariant (Q4; criterion 11): two cancels at once, one wins, one release. */
  @Test
  void concurrentCancelsReleaseTheHoldExactlyOnce() throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    int requests = 4;
    CyclicBarrier start = new CyclicBarrier(requests);
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<MockHttpServletResponse>> results = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return cancel(id, sender);
                }));
      }
      for (Future<MockHttpServletResponse> result : results) {
        statuses.add(result.get(60, TimeUnit.SECONDS).getStatus());
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409, 409);
    assertThat(reviewRows(id)).hasSize(1);
    assertThat(reviewAudits(sender, id)).hasSize(1);
    assertBalances(sender, "2000.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  private List<Map<String, Object>> reviewAudits(Account actor, UUID transferId) {
    return jdbc.queryForList(
        "SELECT actor_type, result, old_value::text AS old_value, new_value::text AS new_value"
            + " FROM audit.audit_logs WHERE actor_id = ? AND action = 'TRANSFER_REVIEW'"
            + " AND resource_id = ? ORDER BY id",
        actor.id(),
        transferId.toString());
  }
}
