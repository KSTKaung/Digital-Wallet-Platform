package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.support.OutboxRows.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The deadline crossed between the decision's own check and the status {@code UPDATE} (doc 08 §5,
 * V11 notes: "a boundary test proves the caller never gets 500"; MVP-ADR-24 "Concurrency"), against
 * PostgreSQL and deterministically: the transfer is overdue, and the {@code databaseNow()} spy
 * reports a time one hour earlier, so the decision goes ahead (for an approval: the capture, the
 * recipient's credit and the ledger posting) and the V4 guard refuses the {@code UPDATE} with
 * {@code restrict_violation}. The answer is {@code 409}, never {@code 500}, and the whole
 * transaction rolls back: no posting, no capture, no release, no review row, no audit row, no
 * email. The next action, or the expiry job, then expires the transfer once.
 */
class TransferStaffReviewBoundaryIT extends TransferReviewFlowSupport {

  private List<String> emailKinds(Account account) {
    return transferEmails(account).stream().map(Row::kind).toList();
  }

  @ParameterizedTest
  @ValueSource(strings = {"approve", "reject"})
  void theDeadlineCrossedBeforeTheUpdateIs409AndRollsEverythingBack(String action)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    UUID id = held(sender, recipient);
    String reference = (String) transferRow(id).get("reference");
    overdue(id, 60);
    doAnswer(invocation -> ((Instant) invocation.callRealMethod()).minus(Duration.ofHours(1)))
        .when(transferRowsSpy)
        .databaseNow();

    Mockito.clearInvocations(ledgerRowsSpy);
    MockHttpServletResponse crossed;
    try {
      crossed = act(action, id, reviewer);
    } finally {
      Mockito.reset(transferRowsSpy);
    }

    assertThat(crossed.getStatus()).as(crossed.getContentAsString()).isEqualTo(409);
    assertThat(errorCode(crossed)).isEqualTo("INVALID_TRANSFER_STATE");
    assertThat(transferRow(id))
        .containsEntry("status", "PENDING_REVIEW")
        .containsEntry("ledger_status", null)
        .containsEntry("decided_at", null);
    // The approval did post (header and both entries) before the guard refused the UPDATE ...
    if (action.equals("approve")) {
      Mockito.verify(ledgerRowsSpy)
          .insertTransaction(
              any(), org.mockito.ArgumentMatchers.eq(reference), any(), any(), any());
      Mockito.verify(ledgerRowsSpy, Mockito.times(2))
          .insertEntry(any(), any(), any(), any(), any());
    }
    // ... and nothing of it is left: the posting rolled back with the refused UPDATE.
    assertThat(ledgerTransactions(reference)).isZero();
    assertBalances(sender, "700.00", "1300.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("1300.00");
    assertThat(transferUsed(sender)).isEqualByComparingTo("0");
    assertThat(reviewRows(id)).isEmpty();
    assertThat(reviewAuditsOf(id)).isEmpty();
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD");
    assertThat(transferEmails(recipient)).isEmpty();

    // The next action expires it (case 6), once.
    MockHttpServletResponse next = act(action, id, reviewer);
    assertThat(next.getStatus()).isEqualTo(409);
    assertThat(transferRow(id)).containsEntry("status", "EXPIRED");
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    assertThat(ledgerTransactions(reference)).isZero();
    assertThat(reviewRows(id))
        .singleElement()
        .satisfies(
            review ->
                assertThat(review)
                    .containsEntry("action", "EXPIRE")
                    .containsEntry("requested_action", action.toUpperCase(java.util.Locale.ROOT))
                    .containsEntry("actor_user_id", reviewer.id()));
    assertThat(reviewAuditsOf(id)).hasSize(1);
    assertThat(emailKinds(sender)).containsExactly("TRANSFER_HELD", "TRANSFER_EXPIRED");
    assertThat(transferEmails(recipient)).isEmpty();
  }

  private MockHttpServletResponse act(String action, UUID id, Account reviewer) throws Exception {
    return action.equals("approve")
        ? approveReview(id, reviewer)
        : rejectReview(id, "Unusual activity", reviewer);
  }
}
