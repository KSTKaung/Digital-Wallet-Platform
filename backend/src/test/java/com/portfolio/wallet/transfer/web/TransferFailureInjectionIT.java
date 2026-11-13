package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.risk.persistence.RiskReviewRepository;
import com.portfolio.wallet.transfer.application.TransferExpiryService;
import com.portfolio.wallet.transfer.persistence.TransferIdempotencyRecordRepository;
import com.portfolio.wallet.transfer.persistence.TransferRepository.LockMode;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRepository;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Failure injection at <b>every write point</b> of the transfer money transactions
 * (documentations/06 MVP-ADR-03, -06, -24, -25; doc 08 §4C.4 criteria 5, 9, 11, 19; PR 5a step 7).
 *
 * <p>For each point the real write runs first and then the call fails, so the test proves that a
 * write which <i>did</i> happen is rolled back, not merely that a later write never happened. After
 * the failure, both parties' wallets (available and reserved), the day's transfer usage (used and
 * reserved), the ledger, the transfer rows, the review rows, the audit rows, the outbox rows and
 * the idempotency records are exactly as before; the client gets {@code 500 SYSTEM_ERROR} without
 * the injected text; and a retry (the same key for a create, the same action for a cancel, the next
 * run for the expiry) runs fresh and succeeds exactly once.
 *
 * <p>Paths: complete ({@code ALLOW}), hold ({@code MANUAL_REVIEW}), the stored {@code BLOCK}, the
 * customer cancel and the expiry job. The risk assessment commits in its own transaction before the
 * money transaction and is allowed to stay as an orphan (Q4); it is not part of the snapshot.
 *
 * <p>Uses its own Spring context (four extra repository spies). Every assertion reads only this
 * test's own rows: the expiry job runs over the shared database.
 */
class TransferFailureInjectionIT extends TransferReviewFlowSupport {

  private static final String INJECTED = "injected failure 7f3e";

  @MockitoSpyBean WalletRepository walletRowsSpy;
  @MockitoSpyBean DailyLimitUsageRepository usageRowsSpy;
  @MockitoSpyBean TransferIdempotencyRecordRepository transferKeysSpy;
  @MockitoSpyBean RiskReviewRepository reviewRowsSpy;

  @Autowired TransferExpiryService expiry;

  /** Write points of {@code POST /transfers}, in the order the money transaction reaches them. */
  enum CreatePoint {
    /** Both wallets are locked in one statement. */
    AFTER_WALLET_LOCKS,
    /** Both snapshots and the day's usage are written (complete), or the hold and its usage. */
    AFTER_BALANCES_AND_USAGE,
    /** The ledger header and both entries are written (complete only). */
    AFTER_LEDGER_POSTING,
    /** The {@code COMPLETED} or {@code PENDING_REVIEW} transfer row is inserted. */
    AFTER_TRANSFER_ROW,
    /** The {@code TRANSFER} audit row is inserted. */
    AFTER_AUDIT_ROW,
    /** The first email row ({@code TRANSFER_SENT} or {@code TRANSFER_HELD}) is inserted. */
    AT_EMAIL_ROW,
    /** The response is stored on the idempotency record: the last write before the commit. */
    AT_IDEMPOTENCY_COMPLETE
  }

  /** Write points of the cancel and of the expiry job ({@code HeldTransferDecider}). */
  enum DecisionPoint {
    /** The transfer row is locked in a statement of its own. */
    AFTER_TRANSFER_ROW_LOCK,
    /** Both wallets are locked. */
    AFTER_WALLET_LOCKS,
    /** The sender's snapshot and the stored day's usage are released. */
    AFTER_RELEASE,
    /** The status {@code UPDATE} (with the version check) is written. */
    AFTER_STATUS_UPDATE,
    /** The risk review row is inserted. */
    AFTER_REVIEW_ROW,
    /** The {@code TRANSFER_REVIEW} audit row is inserted. */
    AFTER_AUDIT_ROW,
    /** The {@code TRANSFER_CANCELLED} or {@code TRANSFER_EXPIRED} email row is inserted. */
    AT_EMAIL_ROW
  }

  /** Write points of the stored {@code BLOCK} (its refusal transaction). */
  enum BlockPoint {
    AFTER_BLOCKED_ROW,
    AFTER_AUDIT_ROW
  }

  private static Answer<Object> callRealThenFail() {
    return invocation -> {
      invocation.callRealMethod();
      throw new IllegalStateException(INJECTED);
    };
  }

  private void resetSpies() {
    Mockito.reset(
        transferRowsSpy,
        ledgerRowsSpy,
        auditRowsSpy,
        notificationRowsSpy,
        walletRowsSpy,
        usageRowsSpy,
        transferKeysSpy,
        reviewRowsSpy);
  }

  // Create: complete and hold -------------------------------------------------------------------

  private void failCreateAt(CreatePoint point, Account sender, Account recipient, boolean hold) {
    UUID senderWallet = walletId(sender);
    UUID recipientWallet = walletId(recipient);
    switch (point) {
      case AFTER_WALLET_LOCKS ->
          doAnswer(callRealThenFail())
              .when(walletRowsSpy)
              .lockBothForUpdate(senderWallet, recipientWallet);
      case AFTER_BALANCES_AND_USAGE ->
          doAnswer(callRealThenFail())
              .when(usageRowsSpy)
              .updateUsedAndReserved(
                  eq(senderWallet), eq("TRANSFER"), any(), any(), any(), anyLong());
      case AFTER_LEDGER_POSTING -> {
        // The second entry of the one posting of this request: header and both entries written.
        AtomicInteger entries = new AtomicInteger();
        doAnswer(
                invocation -> {
                  invocation.callRealMethod();
                  if (entries.incrementAndGet() == 2) {
                    throw new IllegalStateException(INJECTED);
                  }
                  return null;
                })
            .when(ledgerRowsSpy)
            .insertEntry(any(), any(), any(), any(), any());
      }
      case AFTER_TRANSFER_ROW ->
          doAnswer(callRealThenFail()).when(transferRowsSpy).insert(any(), anyLong());
      case AFTER_AUDIT_ROW ->
          doAnswer(callRealThenFail())
              .when(auditRowsSpy)
              .insert(
                  argThat(
                      row ->
                          row != null
                              && "TRANSFER".equals(row.action())
                              && sender.id().equals(row.actorId())));
      case AT_EMAIL_ROW ->
          doAnswer(callRealThenFail())
              .when(notificationRowsSpy)
              .insert(
                  any(),
                  eq(sender.id()),
                  eq(hold ? "TRANSFER_HELD" : "TRANSFER_SENT"),
                  any(),
                  any());
      case AT_IDEMPOTENCY_COMPLETE ->
          doAnswer(callRealThenFail())
              .when(transferKeysSpy)
              .complete(eq(sender.id()), any(), any(), eq(201), any());
    }
  }

  /** Criterion 5: a completion that fails at any write point leaves nothing; the retry works. */
  @ParameterizedTest
  @EnumSource(CreatePoint.class)
  void aCompletionThatFailsAtAnyWritePointLeavesNothingAndTheRetrySucceeds(CreatePoint point)
      throws Exception {
    Account sender = funded("5000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    Map<String, Object> senderBefore = snapshot(sender);
    Map<String, Object> recipientBefore = snapshot(recipient);
    failCreateAt(point, sender, recipient, false);

    MockHttpServletResponse failed = transfer(sender, recipient, ALLOW, key);

    assertSystemError(failed);
    assertThat(snapshot(sender)).as("sender after " + point).isEqualTo(senderBefore);
    assertThat(snapshot(recipient)).as("recipient after " + point).isEqualTo(recipientBefore);
    assertTotalMatchesLedger(sender);
    assertTotalMatchesLedger(recipient);

    resetSpies();
    MockHttpServletResponse retried = transfer(sender, recipient, ALLOW, key);

    assertThat(retried.getStatus()).as(retried.getContentAsString()).isEqualTo(201);
    assertThat(body(retried).get("status").asString()).isEqualTo("COMPLETED");
    assertThat(body(retried).get("ledgerStatus").asString()).isEqualTo("POSTED");
    assertBalances(sender, "4000.01", "0.00");
    assertBalances(recipient, "999.99", "0.00");
    assertThat(transferUsed(sender)).isEqualByComparingTo(ALLOW);
    assertThat(transfersOf(sender))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row).containsEntry("status", "COMPLETED");
              assertBalancedPosting((String) row.get("reference"), ALLOW);
            });
    assertThat(transferAudits(sender)).hasSize(1);
    assertThat(transferRecords(sender, key)).isOne();
    assertThat(transferEmails(sender)).extracting(e -> e.kind()).containsExactly("TRANSFER_SENT");
    assertThat(transferEmails(recipient))
        .extracting(e -> e.kind())
        .containsExactly("TRANSFER_RECEIVED");
    // The failed attempt's assessment stays as an orphan (Q4), unless the failure came first.
    assertThat(assessments(sender)).isEqualTo(2);
  }

  /** Criteria 5, 6, 11: a hold that fails at any write point reserves nothing; the retry holds. */
  @ParameterizedTest
  @EnumSource(
      value = CreatePoint.class,
      names = "AFTER_LEDGER_POSTING",
      mode = EnumSource.Mode.EXCLUDE)
  void aHoldThatFailsAtAnyWritePointReservesNothingAndTheRetryHolds(CreatePoint point)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    Map<String, Object> senderBefore = snapshot(sender);
    Map<String, Object> recipientBefore = snapshot(recipient);
    failCreateAt(point, sender, recipient, true);

    MockHttpServletResponse failed = transfer(sender, recipient, HOLD, key);

    assertSystemError(failed);
    assertThat(snapshot(sender)).as("sender after " + point).isEqualTo(senderBefore);
    assertThat(snapshot(recipient)).as("recipient after " + point).isEqualTo(recipientBefore);
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");

    resetSpies();
    MockHttpServletResponse retried = transfer(sender, recipient, HOLD, key);

    assertThat(retried.getStatus()).as(retried.getContentAsString()).isEqualTo(201);
    assertThat(body(retried).get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo(HOLD);
    assertThat(transferUsed(sender)).isEqualByComparingTo("0");
    assertThat(transfersOf(sender))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("status", "PENDING_REVIEW"));
    assertThat(transferRecords(sender, key)).isOne();
    assertThat(transferEmails(sender)).extracting(e -> e.kind()).containsExactly("TRANSFER_HELD");
    assertThat(transferEmails(recipient)).isEmpty();
  }

  /** Q3: the stored BLOCK commits with its row and audit, or not at all; the retry blocks again. */
  @ParameterizedTest
  @EnumSource(BlockPoint.class)
  void aBlockWhoseRefusalTransactionFailsStoresNothingAndTheRetryBlocks(BlockPoint point)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID key = UUID.randomUUID();
    Map<String, Object> senderBefore = snapshot(sender);
    Map<String, Object> recipientBefore = snapshot(recipient);
    switch (point) {
      case AFTER_BLOCKED_ROW ->
          doAnswer(callRealThenFail()).when(transferRowsSpy).insert(any(), anyLong());
      case AFTER_AUDIT_ROW ->
          doAnswer(callRealThenFail())
              .when(auditRowsSpy)
              .insert(
                  argThat(
                      row ->
                          row != null
                              && "TRANSFER".equals(row.action())
                              && sender.id().equals(row.actorId())));
    }

    MockHttpServletResponse failed = transfer(sender, recipient, BLOCK, key);

    assertSystemError(failed);
    assertThat(snapshot(sender)).as("sender after " + point).isEqualTo(senderBefore);
    assertThat(snapshot(recipient)).as("recipient after " + point).isEqualTo(recipientBefore);

    resetSpies();
    MockHttpServletResponse retried = transfer(sender, recipient, BLOCK, key);

    assertThat(retried.getStatus()).isEqualTo(403);
    assertThat(errorCode(retried)).isEqualTo("RISK_BLOCKED");
    assertThat(transfersOf(sender))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("status", "BLOCKED"));
    assertThat(transferRecords(sender, key)).isOne();
    assertThat(transferAudits(sender)).hasSize(1);
    assertBalances(sender, "2000.00", "0.00");
    assertThat(transferEmails(sender)).isEmpty();
  }

  // Cancel and expiry ---------------------------------------------------------------------------

  private void failDecisionAt(
      DecisionPoint point, UUID transferId, Account sender, Account recipient, boolean byJob) {
    UUID senderWallet = walletId(sender);
    UUID recipientWallet = walletId(recipient);
    switch (point) {
      case AFTER_TRANSFER_ROW_LOCK -> {
        if (byJob) {
          doAnswer(callRealThenFail())
              .when(transferRowsSpy)
              .lockForUpdate(transferId, LockMode.SKIP_LOCKED);
        } else {
          doAnswer(callRealThenFail()).when(transferRowsSpy).lockSentBy(transferId, sender.id());
        }
      }
      case AFTER_WALLET_LOCKS ->
          doAnswer(callRealThenFail())
              .when(walletRowsSpy)
              .lockBothForUpdate(senderWallet, recipientWallet);
      case AFTER_RELEASE ->
          doAnswer(callRealThenFail())
              .when(usageRowsSpy)
              .updateUsedAndReserved(
                  eq(senderWallet), eq("TRANSFER"), any(), any(), any(), anyLong());
      case AFTER_STATUS_UPDATE ->
          doAnswer(callRealThenFail())
              .when(transferRowsSpy)
              .updateDecision(eq(transferId), any(), any(), any(), anyLong());
      case AFTER_REVIEW_ROW ->
          doAnswer(callRealThenFail())
              .when(reviewRowsSpy)
              .insert(eq(transferId), any(), any(), any(), any(), any(), any(), any(), any());
      case AFTER_AUDIT_ROW ->
          doAnswer(callRealThenFail())
              .when(auditRowsSpy)
              .insert(
                  argThat(
                      row ->
                          row != null
                              && "TRANSFER_REVIEW".equals(row.action())
                              && transferId.toString().equals(row.resourceId())));
      case AT_EMAIL_ROW ->
          doAnswer(callRealThenFail())
              .when(notificationRowsSpy)
              .insert(
                  any(),
                  eq(sender.id()),
                  eq(byJob ? "TRANSFER_EXPIRED" : "TRANSFER_CANCELLED"),
                  any(),
                  any());
    }
  }

  /** Criteria 7, 11: a cancel that fails at any write point keeps the hold; the retry releases. */
  @ParameterizedTest
  @EnumSource(DecisionPoint.class)
  void aCancelThatFailsAtAnyWritePointKeepsTheHoldAndTheRetryReleasesItOnce(DecisionPoint point)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    Map<String, Object> senderBefore = snapshot(sender);
    Map<String, Object> recipientBefore = snapshot(recipient);
    failDecisionAt(point, id, sender, recipient, false);

    MockHttpServletResponse failed = cancel(id, sender);

    assertSystemError(failed);
    assertThat(snapshot(sender)).as("sender after " + point).isEqualTo(senderBefore);
    assertThat(snapshot(recipient)).as("recipient after " + point).isEqualTo(recipientBefore);
    assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");

    resetSpies();
    MockHttpServletResponse retried = cancel(id, sender);

    assertThat(retried.getStatus()).as(retried.getContentAsString()).isEqualTo(200);
    assertReleasedOnce(id, sender, recipient, "CANCELLED", "TRANSFER_CANCELLED");
  }

  /** Criteria 8, 11: an expiry that fails at any write point keeps the hold; the next run works. */
  @ParameterizedTest
  @EnumSource(DecisionPoint.class)
  void anExpiryThatFailsAtAnyWritePointKeepsTheHoldAndTheNextRunReleasesItOnce(DecisionPoint point)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    overdue(id, 60);
    Map<String, Object> senderBefore = snapshot(sender);
    Map<String, Object> recipientBefore = snapshot(recipient);
    failDecisionAt(point, id, sender, recipient, true);

    expiry.expireDue();

    assertThat(snapshot(sender)).as("sender after " + point).isEqualTo(senderBefore);
    assertThat(snapshot(recipient)).as("recipient after " + point).isEqualTo(recipientBefore);
    assertThat(transferRow(id)).containsEntry("status", "PENDING_REVIEW");
    assertBalances(sender, "700.00", "1300.00");

    resetSpies();
    expiry.expireDue();

    assertReleasedOnce(id, sender, recipient, "EXPIRED", "TRANSFER_EXPIRED");
  }

  /** The hold of {@code id} was released exactly once, by one decision with one of each record. */
  private void assertReleasedOnce(
      UUID id, Account sender, Account recipient, String status, String emailKind) {
    assertThat(transferRow(id)).containsEntry("status", status);
    assertBalances(sender, "2000.00", "0.00");
    assertBalances(recipient, "0.00", "0.00");
    assertThat(transferReserved(sender)).isEqualByComparingTo("0");
    assertThat(transferUsed(sender)).isEqualByComparingTo("0");
    assertThat(reviewRows(id)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'TRANSFER_REVIEW'"
                    + " AND resource_id = ?",
                Integer.class,
                id.toString()))
        .isOne();
    assertThat(transferEmails(sender))
        .extracting(e -> e.kind())
        .containsExactly("TRANSFER_HELD", emailKind);
    assertThat(transferEmails(recipient)).isEmpty();
  }

  // Helpers -------------------------------------------------------------------------------------

  private void assertSystemError(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(500);
    assertThat(errorCode(response)).isEqualTo("SYSTEM_ERROR");
    assertThat(response.getContentAsString()).doesNotContain(INJECTED);
  }

  /**
   * Everything a transfer transaction can write for {@code account}, as committed now: wallet
   * snapshot, transfer usage per day, ledger transactions on its customer account, its transfers
   * (as sender or receiver, with status and version), review rows, audit rows, outbox rows and
   * transfer idempotency records.
   */
  private Map<String, Object> snapshot(Account account) {
    UUID wallet = walletId(account);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put(
        "wallet",
        jdbc.queryForMap(
                "SELECT available_balance, reserved_balance, version FROM wallet.wallets"
                    + " WHERE id = ?",
                wallet)
            .toString());
    snapshot.put(
        "usage",
        jdbc.queryForList(
                "SELECT limit_date, used, reserved, version FROM wallet.daily_limit_usage"
                    + " WHERE wallet_id = ? AND operation = 'TRANSFER' ORDER BY limit_date",
                wallet)
            .toString());
    snapshot.put(
        "ledger",
        jdbc.queryForObject(
            "SELECT count(DISTINCT e.transaction_id) FROM ledger.ledger_entries e"
                + " JOIN ledger.ledger_accounts a ON a.id = e.account_id"
                + " WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER'",
            Long.class,
            wallet));
    snapshot.put(
        "ledgerBalance", ledgerBalance(account).setScale(2, java.math.RoundingMode.UNNECESSARY));
    snapshot.put(
        "transfers",
        jdbc.queryForList(
                "SELECT id, status, version, ledger_status, decided_at FROM transfer.transfers"
                    + " WHERE sender_customer_id = ? OR receiver_customer_id = ? ORDER BY id",
                account.id(),
                account.id())
            .toString());
    snapshot.put(
        "reviews",
        jdbc.queryForObject(
            "SELECT count(*) FROM risk.risk_reviews r JOIN transfer.transfers t"
                + " ON t.id = r.transfer_id WHERE t.sender_customer_id = ?",
            Long.class,
            account.id()));
    snapshot.put(
        "audits",
        jdbc.queryForObject(
            "SELECT count(*) FROM audit.audit_logs WHERE action IN ('TRANSFER', 'TRANSFER_REVIEW')"
                + " AND (actor_id = ? OR resource_id IN (SELECT id::text FROM transfer.transfers"
                + " WHERE sender_customer_id = ?))",
            Long.class,
            account.id(),
            account.id()));
    snapshot.put(
        "outbox",
        jdbc.queryForObject(
            "SELECT count(*) FROM notification.notifications WHERE user_id = ?",
            Long.class,
            account.id()));
    snapshot.put(
        "idempotencyRecords",
        jdbc.queryForObject(
            "SELECT count(*) FROM transfer.idempotency_records WHERE customer_id = ?",
            Long.class,
            account.id()));
    return snapshot;
  }
}
