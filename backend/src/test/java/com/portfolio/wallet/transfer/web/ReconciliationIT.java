package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.admin.application.ReconciliationMismatch;
import com.portfolio.wallet.admin.application.ReconciliationMismatch.Kind;
import com.portfolio.wallet.admin.application.ReconciliationReport;
import com.portfolio.wallet.admin.application.ReconciliationService;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads;
import com.portfolio.wallet.risk.application.RiskReconciliationReads;
import com.portfolio.wallet.transfer.application.TransferExpiryService;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * The scheduled reconciliation against PostgreSQL (documentations/06 MVP-ADR-03 checks 1–5 and Σ
 * DEBIT = Σ CREDIT; MVP-ADR-22 Y5; MVP-ADR-24 "Review outcomes", Q4; doc 08 §4C.4 criterion 18).
 *
 * <p><b>Own fixtures only</b> (doc 08 §5, "For step 12"): the test database is shared by the whole
 * suite, and other tests leave rows that are inconsistent on purpose (trigger bypasses, {@code
 * TST-} and {@code TX-} postings without a business record, wallets without ledger accounts). A run
 * therefore reports mismatches that are not this test's; every assertion is about the ids this test
 * created, and the production code has no test prefix. Injections use the runtime role where it may
 * write, and the bootstrap superuser with triggers disabled only for states the runtime role cannot
 * create (a decided transfer changed afterwards, an unbalanced ledger transaction). Rows stay:
 * nothing can delete them, and later runs keep reporting them under their own ids.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationIT extends TransferReviewFlowSupport {

  private static final String REASON = "Does not match the customer profile";

  @Autowired ReconciliationService reconciliation;
  @Autowired TransferExpiryService expiry;
  @Autowired WalletReconciliationReads walletReads;
  @Autowired TransferReconciliationReads transferReads;
  @Autowired RiskReconciliationReads riskReads;
  @Autowired LedgerReconciliationReads ledgerReads;

  /** A run and the log lines it wrote. */
  private record Run(ReconciliationReport report, List<String> lines) {

    List<ReconciliationMismatch> involving(Object id) {
      return report.involving(id);
    }

    /** The kinds reported for {@code id}. */
    List<Kind> kinds(Object id) {
      return involving(id).stream().map(ReconciliationMismatch::kind).toList();
    }

    /** The one log line of the mismatch of {@code kind} for {@code id}, which must be an ERROR. */
    String errorLine(Kind kind, Object id) {
      List<String> matching =
          lines.stream()
              .filter(
                  l ->
                      l.contains(
                          "RECONCILIATION_MISMATCH: check="
                              + kind.check()
                              + ", kind="
                              + kind.name()
                              + ","))
              .filter(l -> l.contains("=" + id))
              .toList();
      assertThat(matching)
          .as("a RECONCILIATION_MISMATCH line of kind %s for id %s", kind, id)
          .isNotEmpty();
      String line = matching.getFirst();
      assertThat(line).contains("\"level\":\"ERROR\"");
      return line;
    }
  }

  private Run run(CapturedOutput output) {
    int start = output.getAll().length();
    ReconciliationReport report = reconciliation.reconcile();
    assertThat(report.completed()).isTrue();
    List<String> lines = Arrays.asList(output.getAll().substring(start).split("\\R"));
    assertThat(lines)
        .anySatisfy(
            l -> assertThat(l).contains("Reconciliation run: mismatches=").contains("\"INFO\""));
    return new Run(report, lines);
  }

  private void setWalletStatus(Account owner, String status) throws Exception {
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE wallet.wallets SET status = ? WHERE customer_id = ?",
                status,
                owner.id()));
  }

  private MockHttpServletResponse withdraw(Account customer, String amount) throws Exception {
    return send(
        post("/api/v1/wallet/withdrawals")
            .contentType(MediaType.APPLICATION_JSON)
            .header(KEY_HEADER, UUID.randomUUID().toString())
            .content("{\"amount\":" + amount + ",\"currency\":\"THB\"}"),
        customer.token());
  }

  /** Every id of the customers' wallets, accounts, movements, postings and transfers. */
  private Set<Object> idsOf(Account... customers) {
    Set<Object> ids = new HashSet<>();
    for (Account customer : customers) {
      UUID wallet = walletId(customer);
      ids.add(wallet);
      ids.addAll(
          jdbc.queryForList(
              "SELECT id FROM ledger.ledger_accounts WHERE owner_reference = ?",
              UUID.class,
              wallet));
      ids.addAll(
          jdbc.queryForList(
              "SELECT DISTINCT e.transaction_id FROM ledger.ledger_entries e"
                  + " JOIN ledger.ledger_accounts a ON a.id = e.account_id"
                  + " WHERE a.owner_reference = ?",
              UUID.class,
              wallet));
      ids.addAll(
          jdbc.queryForList(
              "SELECT id FROM wallet.deposits WHERE wallet_id = ?"
                  + " UNION ALL SELECT id FROM wallet.withdrawals WHERE wallet_id = ?",
              UUID.class,
              wallet,
              wallet));
      ids.addAll(
          jdbc.queryForList(
              "SELECT id FROM transfer.transfers WHERE sender_wallet_id = ? OR receiver_wallet_id = ?",
              UUID.class,
              wallet,
              wallet));
      ids.addAll(
          jdbc.queryForList(
              "SELECT r.id FROM risk.risk_reviews r JOIN transfer.transfers t ON t.id = r.transfer_id"
                  + " WHERE t.sender_wallet_id = ?",
              UUID.class,
              wallet));
    }
    return ids;
  }

  private static List<ReconciliationMismatch> involvingAny(Run run, Set<Object> ids) {
    return run.report().mismatches().stream()
        .filter(m -> ids.stream().anyMatch(m::involves))
        .toList();
  }

  private LocalDate limitDate(UUID transferId) {
    return ((Date) transferRow(transferId).get("limit_date")).toLocalDate();
  }

  // Consistent data -----------------------------------------------------------------------------

  /**
   * Real flows of every kind (deposits, a withdrawal, a completed, a blocked and a still-held
   * transfer, and the seven D2 review cases) leave nothing for any check to report.
   */
  @Test
  void consistentFlowsOfEveryKindPassEveryCheck(CapturedOutput output) throws Exception {
    Account sender = funded("20000.00");
    Account recipient = withWallet();
    Account paid = withWallet();
    Account unavailable = withWallet();
    Account reviewer = officer();
    assertThat(withdraw(sender, "100.00").getStatus()).as("withdrawal").isEqualTo(201);
    // Every hold before any completion: a completed transfer makes its recipient known, and the
    // test policy's amounts then score one point less (no longer a hold).
    UUID stillHeld = held(sender, recipient);
    UUID approved = held(sender, recipient);
    UUID rejected = held(sender, recipient);
    UUID systemRejected = held(sender, unavailable);
    UUID cancelled = held(sender, recipient);
    UUID expiredByJob = held(sender, recipient);
    UUID lateApproval = held(sender, recipient);
    UUID lateCancel = held(sender, recipient);
    assertThat(transfer(sender, paid, BLOCK).getStatus()).as("blocked").isEqualTo(403);
    assertThat(transfer(sender, paid, ALLOW).getStatus()).as("completed").isEqualTo(201);
    // 1 staff approval, 2 staff rejection, 3 system rejection, 4 cancel.
    assertThat(approveReview(approved, reviewer).getStatus()).isEqualTo(200);
    assertThat(rejectReview(rejected, REASON, reviewer).getStatus()).isEqualTo(200);
    setWalletStatus(unavailable, "FROZEN");
    assertThat(approveReview(systemRejected, reviewer).getStatus()).isEqualTo(200);
    assertThat(transferRow(systemRejected))
        .containsEntry("rejection_reason_category", "WALLET_UNAVAILABLE");
    assertThat(cancel(cancelled, sender).getStatus()).isEqualTo(200);
    // 5 expiry by the job, 6 a late staff action, 7 a late cancel.
    overdue(expiredByJob, 60);
    overdue(lateApproval, 60);
    overdue(lateCancel, 60);
    assertThat(approveReview(lateApproval, reviewer).getStatus()).isEqualTo(409);
    assertThat(cancel(lateCancel, sender).getStatus()).isEqualTo(409);
    expiry.expireDue();
    for (UUID id : List.of(expiredByJob, lateApproval, lateCancel)) {
      assertThat(transferRow(id)).containsEntry("status", "EXPIRED");
    }
    assertThat(transferRow(stillHeld)).containsEntry("status", "PENDING_REVIEW");
    Set<Object> own = idsOf(sender, recipient, paid, unavailable);
    assertThat(own).contains(stillHeld, approved, rejected, systemRejected, cancelled);

    Run run = run(output);

    assertThat(involvingAny(run, own)).isEmpty();
    // Every module was read; other tests' rows are in the counts too.
    assertThat(run.report().rowsRead())
        .containsKeys("wallets", "deposits", "withdrawals", "everHeldTransfers", "reviews")
        .allSatisfy((name, rows) -> assertThat(rows).as(name).isPositive());
  }

  // Check 1 -------------------------------------------------------------------------------------

  @Test
  void aWalletSnapshotThatDiffersFromItsLedgerBalanceIsLoggedAsCheck1(CapturedOutput output)
      throws Exception {
    Account customer = funded("500.00");
    UUID wallet = walletId(customer);
    jdbc.update(
        "UPDATE wallet.wallets SET available_balance = available_balance + 0.01 WHERE id = ?",
        wallet);

    Run run = run(output);

    assertThat(run.kinds(wallet)).containsExactly(Kind.WALLET_BALANCE_DIFFERS);
    String line = run.errorLine(Kind.WALLET_BALANCE_DIFFERS, wallet);
    assertThat(line)
        .contains(
            "RECONCILIATION_MISMATCH: check=1, kind=WALLET_BALANCE_DIFFERS, walletId=" + wallet)
        // Ids only: never the balance or the wallet number.
        .doesNotContain("500.01")
        .doesNotContain("500.00")
        .doesNotContain(numberOf(customer));
  }

  // Check 2 -------------------------------------------------------------------------------------

  @Test
  void completedMovementsWithoutTheirPostingAndPostingsWithoutRecordAreLoggedAsCheck2(
      CapturedOutput output) throws Exception {
    Account customer = funded("500.00");
    UUID wallet = walletId(customer);
    // A completed deposit that was never posted.
    Map<String, Object> deposit = validMoneyMovement(wallet);
    insert("wallet.deposits", deposit);
    UUID unposted = (UUID) deposit.get("id");
    // A withdrawal under the reference of the customer's real deposit: posted, but as a DEPOSIT.
    String depositReference =
        jdbc.queryForObject(
            "SELECT reference FROM wallet.deposits WHERE wallet_id = ? AND id <> ?",
            String.class,
            wallet,
            unposted);
    UUID depositPosting =
        jdbc.queryForObject(
            "SELECT id FROM ledger.financial_transactions WHERE transaction_reference = ?",
            UUID.class,
            depositReference);
    Map<String, Object> withdrawal =
        with(validMoneyMovement(wallet), "reference", depositReference);
    insert("wallet.withdrawals", withdrawal);
    UUID wrongType = (UUID) withdrawal.get("id");
    // A completed transfer that was never posted.
    Map<String, Object> transfer = validTransfer("COMPLETED");
    insert("transfer.transfers", transfer);
    UUID unpostedTransfer = (UUID) transfer.get("id");
    // A balanced posting between the two system accounts, with no business record (as the TST-
    // postings of other tests).
    UUID orphanPosting = UUID.randomUUID();
    UUID clearing = cashClearingAccountId();
    UUID suspense = systemAccountId("SYS-SUSPENSE");
    inTransaction(
        c -> {
          insert(
              c,
              "ledger.financial_transactions",
              row(
                  "id",
                  orphanPosting,
                  "transaction_reference",
                  "TST-" + UUID.randomUUID(),
                  "transaction_type",
                  "DEPOSIT",
                  "currency",
                  THB));
          insertEntry(c, orphanPosting, clearing, "DEBIT", "1.00");
          insertEntry(c, orphanPosting, suspense, "CREDIT", "1.00");
        });

    Run run = run(output);

    assertThat(run.kinds(unposted)).containsExactly(Kind.MOVEMENT_NOT_POSTED);
    assertThat(run.errorLine(Kind.MOVEMENT_NOT_POSTED, unposted))
        .contains("check=2, kind=MOVEMENT_NOT_POSTED, recordType=DEPOSIT, recordId=" + unposted);
    assertThat(run.kinds(wrongType)).containsExactly(Kind.MOVEMENT_POSTED_AS_OTHER_TYPE);
    assertThat(run.errorLine(Kind.MOVEMENT_POSTED_AS_OTHER_TYPE, wrongType))
        .contains(
            "recordType=WITHDRAWAL, recordId="
                + wrongType
                + ", ledgerTransactionId="
                + depositPosting);
    assertThat(run.kinds(unpostedTransfer)).containsExactly(Kind.MOVEMENT_NOT_POSTED);
    assertThat(run.errorLine(Kind.MOVEMENT_NOT_POSTED, unpostedTransfer))
        .contains("recordType=TRANSFER, recordId=" + unpostedTransfer);
    assertThat(run.kinds(orphanPosting)).containsExactly(Kind.POSTING_WITHOUT_MOVEMENT);
    assertThat(run.errorLine(Kind.POSTING_WITHOUT_MOVEMENT, orphanPosting))
        .contains("check=2, kind=POSTING_WITHOUT_MOVEMENT, ledgerTransactionId=" + orphanPosting)
        .doesNotContain("TST-");
    // The real deposit keeps its posting: the extra records disturb no other check of the wallet.
    assertThat(run.kinds(wallet)).isEmpty();
    assertThat(run.kinds(depositPosting)).containsExactly(Kind.MOVEMENT_POSTED_AS_OTHER_TYPE);
  }

  // Checks 3 and 4 ------------------------------------------------------------------------------

  @Test
  void aReservedBalanceAndAReservedUsageThatDifferFromTheHoldsAreLoggedAsChecks3And4(
      CapturedOutput output) throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    UUID wallet = walletId(sender);
    LocalDate day = limitDate(id);
    Run consistent = run(output);
    assertThat(consistent.kinds(wallet)).isEmpty();
    assertThat(consistent.kinds(id)).isEmpty();

    // Moved within the wallet, so available + reserved still equals the ledger (check 1 passes).
    jdbc.update(
        "UPDATE wallet.wallets SET available_balance = available_balance - 1,"
            + " reserved_balance = reserved_balance + 1 WHERE id = ?",
        wallet);
    jdbc.update(
        "UPDATE wallet.daily_limit_usage SET reserved = reserved + 1"
            + " WHERE wallet_id = ? AND operation = 'TRANSFER' AND limit_date = ?",
        wallet,
        day);

    Run run = run(output);

    assertThat(run.kinds(wallet))
        .containsExactly(Kind.RESERVED_BALANCE_DIFFERS, Kind.RESERVED_USAGE_DIFFERS);
    assertThat(run.errorLine(Kind.RESERVED_BALANCE_DIFFERS, wallet))
        .contains("check=3, kind=RESERVED_BALANCE_DIFFERS, walletId=" + wallet);
    // The whole message, exactly: ids and the stored day only, so no amount can be in it.
    assertThat(run.errorLine(Kind.RESERVED_USAGE_DIFFERS, wallet))
        .contains(
            "\"message\":\"RECONCILIATION_MISMATCH: check=4, kind=RESERVED_USAGE_DIFFERS, walletId="
                + wallet
                + ", operation=TRANSFER, limitDate="
                + day
                + "\"")
        .doesNotContain("1300.00")
        .doesNotContain("1301.00");
  }

  /**
   * Y5: the holds are compared with the usage of their <b>stored</b> {@code limit_date}. A held
   * transfer whose stored day is the day before, with its reservation on that day's row, is
   * consistent, whatever its {@code created_at} says.
   */
  @Test
  void reservedUsageIsComparedOnTheTransfersStoredLimitDate(CapturedOutput output)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    UUID id = held(sender, recipient);
    UUID wallet = walletId(sender);
    LocalDate day = limitDate(id);
    LocalDate stored = day.minusDays(1);
    withTriggersDisabled(
        superuser -> {
          update(
              superuser, "UPDATE transfer.transfers SET limit_date = ? WHERE id = ?", stored, id);
          update(
              superuser,
              "UPDATE wallet.daily_limit_usage SET reserved = reserved - 1300"
                  + " WHERE wallet_id = ? AND operation = 'TRANSFER' AND limit_date = ?",
              wallet,
              day);
          update(
              superuser,
              "INSERT INTO wallet.daily_limit_usage (wallet_id, operation, limit_date, reserved)"
                  + " VALUES (?, 'TRANSFER', ?, 1300)",
              wallet,
              stored);
        });

    Run run = run(output);

    assertThat(run.kinds(wallet)).isEmpty();
    assertThat(run.kinds(id)).isEmpty();
  }

  // Check 5 (criterion 18) ----------------------------------------------------------------------

  @Test
  void aStaffRejectionPairedWithWalletUnavailableIsLoggedAsCheck5(CapturedOutput output)
      throws Exception {
    Account sender = funded("2000.00");
    UUID id = held(sender, withWallet());
    assertThat(rejectReview(id, REASON, officer()).getStatus()).isEqualTo(200);
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET rejection_reason_category = 'WALLET_UNAVAILABLE'"
                    + " WHERE id = ?",
                id));

    Run run = run(output);

    assertThat(run.kinds(id)).containsExactly(Kind.REVIEW_DISAGREES_WITH_TRANSFER);
    assertThat(run.errorLine(Kind.REVIEW_DISAGREES_WITH_TRANSFER, id))
        .contains("check=5, kind=REVIEW_DISAGREES_WITH_TRANSFER, transferId=" + id + ", reviewId=")
        .doesNotContain(REASON);
    assertThat(run.kinds(walletId(sender))).isEmpty();
  }

  @Test
  void aReviewActionThatDisagreesWithTheFinalStatusIsLoggedAsCheck5(CapturedOutput output)
      throws Exception {
    Account sender = funded("2000.00");
    UUID id = held(sender, withWallet());
    assertThat(cancel(id, sender).getStatus()).isEqualTo(200);
    withTriggersDisabled(
        superuser ->
            update(superuser, "UPDATE transfer.transfers SET status = 'EXPIRED' WHERE id = ?", id));

    Run run = run(output);

    assertThat(run.kinds(id)).containsExactly(Kind.REVIEW_DISAGREES_WITH_TRANSFER);
    assertThat(run.errorLine(Kind.REVIEW_DISAGREES_WITH_TRANSFER, id)).contains("check=5");
  }

  @Test
  void aDecidedTransferWithoutReviewRowIsLoggedAsCheck5(CapturedOutput output) throws Exception {
    Map<String, Object> transfer = validTransfer("CANCELLED");
    OffsetDateTime held = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
    transfer.put("held_at", held);
    transfer.put("review_expires_at", held.plusHours(24));
    transfer.put("decided_at", held.plusMinutes(5));
    insertWithTriggersDisabled("transfer.transfers", transfer);
    UUID id = (UUID) transfer.get("id");

    Run run = run(output);

    assertThat(run.kinds(id)).containsExactly(Kind.DECIDED_WITHOUT_REVIEW);
    assertThat(run.errorLine(Kind.DECIDED_WITHOUT_REVIEW, id))
        .contains("check=5, kind=DECIDED_WITHOUT_REVIEW, transferId=" + id);
  }

  @Test
  void aReviewRowForATransferStillHeldIsLoggedAsCheck5(CapturedOutput output) throws Exception {
    Account sender = funded("2000.00");
    UUID id = held(sender, withWallet());
    UUID assessment = (UUID) transferRow(id).get("risk_assessment_id");
    UUID review = UUID.randomUUID();
    // The runtime role may insert review rows; a valid case 4 row, but the transfer is still held.
    insert(
        "risk.risk_reviews",
        row(
            "id",
            review,
            "transfer_id",
            id,
            "assessment_id",
            assessment,
            "action",
            "CANCEL",
            "decided_by",
            "CUSTOMER",
            "requested_action",
            "CANCEL",
            "actor_user_id",
            sender.id()));

    Run run = run(output);

    assertThat(run.kinds(id)).containsExactly(Kind.REVIEW_WITHOUT_DECIDED_TRANSFER);
    assertThat(run.errorLine(Kind.REVIEW_WITHOUT_DECIDED_TRANSFER, id))
        .contains(
            "check=5, kind=REVIEW_WITHOUT_DECIDED_TRANSFER, transferId="
                + id
                + ", reviewId="
                + review)
        .doesNotContain(sender.id().toString());
    // The hold itself is consistent: checks 3 and 4 find nothing for the sender's wallet.
    assertThat(run.kinds(walletId(sender))).isEmpty();
  }

  /**
   * Q4: an assessment whose money transaction never wrote a transfer, and the investigation of a
   * blocked one, are not flagged.
   */
  @Test
  void orphanAssessmentsAndInvestigationsAreNotFlagged(CapturedOutput output) throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (String decision : List.of("MANUAL_REVIEW", "BLOCK")) {
      UUID assessment = UUID.randomUUID();
      UUID transfer = UUID.randomUUID();
      insert(
          "risk.risk_assessments",
          row(
              "id",
              assessment,
              "transfer_id",
              transfer,
              "customer_id",
              UUID.randomUUID(),
              "amount",
              new BigDecimal("1300.00"),
              "currency",
              THB,
              "score",
              "BLOCK".equals(decision) ? 95 : 75,
              "decision",
              decision));
      ids.add(assessment);
      ids.add(transfer);
      if ("BLOCK".equals(decision)) {
        UUID investigation = UUID.randomUUID();
        insert("risk.investigations", row("id", investigation, "assessment_id", assessment));
        ids.add(investigation);
      }
    }

    Run run = run(output);

    for (UUID id : ids) {
      assertThat(run.involving(id)).as(id.toString()).isEmpty();
      assertThat(run.lines()).noneMatch(line -> line.contains(id.toString()));
    }
  }

  /**
   * A review row for a transfer created {@code COMPLETED} or {@code BLOCKED}, which never entered
   * review: its transfer has no {@code held_at}, so it is not among the ever-held transfers that
   * the transfer module returns, and the row is reported.
   */
  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "BLOCKED"})
  void aReviewRowForATransferThatWasNeverHeldIsLoggedAsCheck5(String status, CapturedOutput output)
      throws Exception {
    Account sender = funded("2000.00");
    Account recipient = withWallet();
    Account reviewer = officer();
    int expected = "COMPLETED".equals(status) ? 201 : 403;
    assertThat(transfer(sender, recipient, "COMPLETED".equals(status) ? ALLOW : BLOCK).getStatus())
        .isEqualTo(expected);
    Map<String, Object> row = transfersOf(sender).getFirst();
    assertThat(row).containsEntry("status", status).containsEntry("held_at", null);
    UUID id = (UUID) row.get("id");
    UUID review = UUID.randomUUID();
    insert(
        "risk.risk_reviews",
        row(
            "id",
            review,
            "transfer_id",
            id,
            "assessment_id",
            row.get("risk_assessment_id"),
            "action",
            "APPROVE",
            "decided_by",
            "STAFF",
            "requested_action",
            "APPROVE",
            "actor_user_id",
            reviewer.id()));

    Run run = run(output);

    assertThat(run.kinds(id)).containsExactly(Kind.REVIEW_WITHOUT_DECIDED_TRANSFER);
    assertThat(run.errorLine(Kind.REVIEW_WITHOUT_DECIDED_TRANSFER, id))
        .contains("transferId=" + id + ", reviewId=" + review);
    assertThat(run.kinds(walletId(sender))).isEmpty();
  }

  /** Held transfers whose sender wallet does not exist: checks 3 and 4, for that wallet id. */
  @Test
  void heldTransfersOfAWalletThatDoesNotExistAreLoggedAsChecks3And4(CapturedOutput output)
      throws Exception {
    Map<String, Object> transfer = validTransfer("PENDING_REVIEW");
    insert("transfer.transfers", transfer);
    UUID unknown = (UUID) transfer.get("sender_wallet_id");
    UUID id = (UUID) transfer.get("id");

    Run run = run(output);

    assertThat(run.kinds(unknown))
        .containsExactly(Kind.HOLD_OF_UNKNOWN_WALLET, Kind.RESERVED_USAGE_DIFFERS);
    assertThat(run.errorLine(Kind.HOLD_OF_UNKNOWN_WALLET, unknown))
        .contains("check=3, kind=HOLD_OF_UNKNOWN_WALLET, walletId=" + unknown + ", side=SENDER");
    // The fixture's recipient wallet does not exist either: reported once, as the other side.
    UUID unknownRecipient = (UUID) transfer.get("receiver_wallet_id");
    assertThat(run.kinds(unknownRecipient)).containsExactly(Kind.HOLD_OF_UNKNOWN_WALLET);
    assertThat(run.errorLine(Kind.HOLD_OF_UNKNOWN_WALLET, unknownRecipient))
        .contains("walletId=" + unknownRecipient + ", side=RECIPIENT");
    assertThat(run.errorLine(Kind.RESERVED_USAGE_DIFFERS, unknown))
        .contains("check=4, kind=RESERVED_USAGE_DIFFERS, walletId=" + unknown)
        .contains("operation=TRANSFER");
    // Still held and never reviewed: nothing for check 5.
    assertThat(run.kinds(id)).isEmpty();
  }

  /** Only transfers are held, so a reservation on a deposit's usage row is check 4. */
  @Test
  void aReservationOnANonTransferUsageRowIsLoggedAsCheck4(CapturedOutput output) throws Exception {
    Account customer = funded("500.00");
    UUID wallet = walletId(customer);
    LocalDate day =
        jdbc.queryForObject(
            "SELECT limit_date FROM wallet.daily_limit_usage"
                + " WHERE wallet_id = ? AND operation = 'DEPOSIT'",
            LocalDate.class,
            wallet);
    jdbc.update(
        "UPDATE wallet.daily_limit_usage SET reserved = 1"
            + " WHERE wallet_id = ? AND operation = 'DEPOSIT' AND limit_date = ?",
        wallet,
        day);

    Run run = run(output);

    assertThat(run.kinds(wallet)).containsExactly(Kind.RESERVED_USAGE_DIFFERS);
    assertThat(run.errorLine(Kind.RESERVED_USAGE_DIFFERS, wallet))
        .contains(
            "check=4, kind=RESERVED_USAGE_DIFFERS, walletId="
                + wallet
                + ", operation=DEPOSIT, limitDate="
                + day);
  }

  @Test
  void aWalletWithoutLedgerAccountAndAnAccountWithoutWalletAreLoggedAsCheck1(CapturedOutput output)
      throws Exception {
    UUID wallet = newWallet();
    UUID account = newCustomerLedgerAccount();
    UUID owner =
        jdbc.queryForObject(
            "SELECT owner_reference FROM ledger.ledger_accounts WHERE id = ?", UUID.class, account);

    Run run = run(output);

    assertThat(run.kinds(wallet)).containsExactly(Kind.WALLET_WITHOUT_LEDGER_ACCOUNT);
    assertThat(run.errorLine(Kind.WALLET_WITHOUT_LEDGER_ACCOUNT, wallet))
        .contains("check=1, kind=WALLET_WITHOUT_LEDGER_ACCOUNT, walletId=" + wallet);
    assertThat(run.kinds(account)).containsExactly(Kind.LEDGER_ACCOUNT_WITHOUT_WALLET);
    assertThat(run.errorLine(Kind.LEDGER_ACCOUNT_WITHOUT_WALLET, account))
        .contains(
            "check=1, kind=LEDGER_ACCOUNT_WITHOUT_WALLET, ledgerAccountId="
                + account
                + ", walletId="
                + owner);
  }

  // Owner decisions of 2026-11-15 --------------------------------------------------------------

  /**
   * A real held transfer whose recipient wallet is then pointed at a wallet that does not exist:
   * the hold itself stays consistent (checks 3 and 4 pass for the sender), and the missing
   * recipient is reported once, as {@code side=RECIPIENT}.
   */
  @Test
  void aHeldTransferToAWalletThatDoesNotExistIsLoggedAsCheck3(CapturedOutput output)
      throws Exception {
    Account sender = funded("2000.00");
    UUID id = held(sender, withWallet());
    UUID missing = UUID.randomUUID();
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET receiver_wallet_id = ? WHERE id = ?",
                missing,
                id));

    Run run = run(output);

    assertThat(run.kinds(missing)).containsExactly(Kind.HOLD_OF_UNKNOWN_WALLET);
    assertThat(run.errorLine(Kind.HOLD_OF_UNKNOWN_WALLET, missing))
        .contains(
            "\"message\":\"RECONCILIATION_MISMATCH: check=3, kind=HOLD_OF_UNKNOWN_WALLET, walletId="
                + missing
                + ", side=RECIPIENT\"");
    assertThat(run.kinds(walletId(sender))).isEmpty();
    assertThat(run.kinds(id)).isEmpty();
  }

  /**
   * A cancelled transfer whose {@code risk_assessment_id} no longer names its review row's
   * assessment (only possible with the triggers off: V4 keeps it immutable). The outcome itself
   * still agrees; only the link is reported.
   */
  @Test
  void aReviewRowNamingAnotherAssessmentThanItsTransferIsLoggedAsCheck5(CapturedOutput output)
      throws Exception {
    Account sender = funded("2000.00");
    UUID id = held(sender, withWallet());
    assertThat(cancel(id, sender).getStatus()).isEqualTo(200);
    UUID reviewAssessment = (UUID) transferRow(id).get("risk_assessment_id");
    UUID other = UUID.randomUUID();
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "UPDATE transfer.transfers SET risk_assessment_id = ? WHERE id = ?",
                other,
                id));
    UUID review =
        jdbc.queryForObject(
            "SELECT id FROM risk.risk_reviews WHERE transfer_id = ?", UUID.class, id);

    Run run = run(output);

    assertThat(run.kinds(id)).containsExactly(Kind.REVIEW_ASSESSMENT_DIFFERS);
    assertThat(run.errorLine(Kind.REVIEW_ASSESSMENT_DIFFERS, id))
        .contains(
            "\"message\":\"RECONCILIATION_MISMATCH: check=5, kind=REVIEW_ASSESSMENT_DIFFERS,"
                + " transferId="
                + id
                + ", reviewId="
                + review
                + ", reviewAssessmentId="
                + reviewAssessment
                + ", transferAssessmentId="
                + other
                + "\"");
  }

  /**
   * A completed deposit whose reference is posted as a balanced {@code DEPOSIT} of another amount,
   * between the template's accounts (cash clearing to the wallet's own account). The snapshot is
   * moved by the posted amount, so check 1 still holds and only the amount differs.
   */
  @Test
  void aPostingOfAnotherAmountThanItsDepositIsLoggedAsCheck2(CapturedOutput output)
      throws Exception {
    Account customer = funded("500.00");
    UUID wallet = walletId(customer);
    UUID account =
        jdbc.queryForObject(
            "SELECT id FROM ledger.ledger_accounts WHERE owner_reference = ?", UUID.class, wallet);
    UUID clearing = cashClearingAccountId();
    String reference = "DEP-" + UUID.randomUUID();
    UUID posting = UUID.randomUUID();
    inTransaction(
        c -> {
          insert(
              c,
              "ledger.financial_transactions",
              row(
                  "id",
                  posting,
                  "transaction_reference",
                  reference,
                  "transaction_type",
                  "DEPOSIT",
                  "currency",
                  THB));
          insertEntry(c, posting, clearing, "DEBIT", "1.00");
          insertEntry(c, posting, account, "CREDIT", "1.00");
          update(
              c,
              "UPDATE wallet.wallets SET available_balance = available_balance + 1 WHERE id = ?",
              wallet);
        });
    Map<String, Object> deposit = with(validMoneyMovement(wallet), "reference", reference);
    insert("wallet.deposits", deposit);
    UUID id = (UUID) deposit.get("id");
    assertThat((BigDecimal) deposit.get("amount")).isEqualByComparingTo("100.00");

    Run run = run(output);

    assertThat(run.kinds(id)).containsExactly(Kind.POSTING_AMOUNT_DIFFERS);
    assertThat(run.errorLine(Kind.POSTING_AMOUNT_DIFFERS, id))
        .contains(
            "\"message\":\"RECONCILIATION_MISMATCH: check=2, kind=POSTING_AMOUNT_DIFFERS,"
                + " recordType=DEPOSIT, recordId="
                + id
                + ", ledgerTransactionId="
                + posting
                + "\"")
        .doesNotContain("100.00");
    assertThat(run.kinds(posting)).containsExactly(Kind.POSTING_AMOUNT_DIFFERS);
    assertThat(run.kinds(wallet)).isEmpty();
  }

  /**
   * A completed transfer between two real wallets whose reference is posted, with its own amount,
   * between the two system accounts instead of the wallets' customer accounts.
   */
  @Test
  void aTransferPostedBetweenOtherAccountsIsLoggedAsCheck2(CapturedOutput output) throws Exception {
    Account sender = funded("500.00");
    Account recipient = withWallet();
    String reference = "TRF-" + UUID.randomUUID();
    UUID posting = systemPosting(reference, "TRANSFER", "100.00");
    Map<String, Object> transfer = validTransfer("COMPLETED");
    transfer.put("reference", reference);
    transfer.put("sender_customer_id", sender.id());
    transfer.put("receiver_customer_id", recipient.id());
    transfer.put("sender_wallet_id", walletId(sender));
    transfer.put("receiver_wallet_id", walletId(recipient));
    insert("transfer.transfers", transfer);
    UUID id = (UUID) transfer.get("id");

    Run run = run(output);

    // The amount is the record's: only the accounts are wrong.
    assertThat(run.kinds(id)).containsExactly(Kind.POSTING_ACCOUNTS_DIFFER);
    assertThat(run.errorLine(Kind.POSTING_ACCOUNTS_DIFFER, id))
        .contains(
            "\"message\":\"RECONCILIATION_MISMATCH: check=2, kind=POSTING_ACCOUNTS_DIFFER,"
                + " recordType=TRANSFER, recordId="
                + id
                + ", ledgerTransactionId="
                + posting
                + "\"");
    assertThat(run.kinds(walletId(sender))).isEmpty();
    assertThat(run.kinds(walletId(recipient))).isEmpty();
  }

  /**
   * A completed deposit or withdrawal of one customer's wallet whose posting, of the right type and
   * amount, moves another customer's account instead (re-check L1, owner 2026-11-18). That other
   * wallet's balance then also disagrees with its ledger (check 1), as it would in production; the
   * record's own wallet is untouched.
   */
  @ParameterizedTest
  @ValueSource(strings = {"DEPOSIT", "WITHDRAWAL"})
  void aDepositOrWithdrawalPostedToAnotherCustomersAccountIsLoggedAsCheck2(
      String type, CapturedOutput output) throws Exception {
    Account owner = funded("500.00");
    Account other = funded("500.00");
    UUID wallet = walletId(owner);
    UUID otherWallet = walletId(other);
    UUID otherAccount =
        jdbc.queryForObject(
            "SELECT id FROM ledger.ledger_accounts WHERE owner_reference = ?",
            UUID.class,
            otherWallet);
    UUID clearing = cashClearingAccountId();
    boolean deposit = "DEPOSIT".equals(type);
    String reference = (deposit ? "DEP-" : "WDR-") + UUID.randomUUID();
    UUID posting = UUID.randomUUID();
    inTransaction(
        c -> {
          insert(
              c,
              "ledger.financial_transactions",
              row(
                  "id",
                  posting,
                  "transaction_reference",
                  reference,
                  "transaction_type",
                  type,
                  "currency",
                  THB));
          // The template's sides, but the other customer's account on the customer side.
          insertEntry(c, posting, deposit ? clearing : otherAccount, "DEBIT", "100.00");
          insertEntry(c, posting, deposit ? otherAccount : clearing, "CREDIT", "100.00");
        });
    Map<String, Object> record = with(validMoneyMovement(wallet), "reference", reference);
    insert(deposit ? "wallet.deposits" : "wallet.withdrawals", record);
    UUID id = (UUID) record.get("id");

    Run run = run(output);

    assertThat(run.kinds(id)).containsExactly(Kind.POSTING_ACCOUNTS_DIFFER);
    assertThat(run.errorLine(Kind.POSTING_ACCOUNTS_DIFFER, id))
        .contains(
            "\"message\":\"RECONCILIATION_MISMATCH: check=2, kind=POSTING_ACCOUNTS_DIFFER,"
                + " recordType="
                + type
                + ", recordId="
                + id
                + ", ledgerTransactionId="
                + posting
                + "\"");
    assertThat(run.kinds(posting)).containsExactly(Kind.POSTING_ACCOUNTS_DIFFER);
    assertThat(run.kinds(wallet)).isEmpty();
    assertThat(run.kinds(otherWallet)).containsExactly(Kind.WALLET_BALANCE_DIFFERS);
  }

  /**
   * A balanced posting of {@code amount} from {@code SYS-CASH-CLEARING} to {@code SYS-SUSPENSE},
   * committed by the runtime role; no customer account moves.
   */
  private UUID systemPosting(String reference, String type, String amount) throws Exception {
    UUID id = UUID.randomUUID();
    UUID clearing = cashClearingAccountId();
    UUID suspense = systemAccountId("SYS-SUSPENSE");
    inTransaction(
        c -> {
          insert(
              c,
              "ledger.financial_transactions",
              row(
                  "id",
                  id,
                  "transaction_reference",
                  reference,
                  "transaction_type",
                  type,
                  "currency",
                  THB));
          insertEntry(c, id, clearing, "DEBIT", amount);
          insertEntry(c, id, suspense, "CREDIT", amount);
        });
    return id;
  }

  /** Each module's reads refuse to run outside a transaction, through the Spring proxy. */
  @Test
  void theModuleReadsRefuseToRunOutsideATransaction() {
    List<ThrowingCallable> reads =
        List.of(
            walletReads::balances,
            walletReads::completedDeposits,
            walletReads::completedWithdrawals,
            walletReads::reservedUsage,
            transferReads::completed,
            transferReads::heldSums,
            transferReads::everHeld,
            riskReads::reviewOutcomes,
            ledgerReads::accountTotals,
            ledgerReads::postings);
    for (ThrowingCallable read : reads) {
      assertThatThrownBy(read).isInstanceOf(IllegalTransactionStateException.class);
    }
  }

  // Σ DEBIT = Σ CREDIT --------------------------------------------------------------------------

  /**
   * An unbalanced ledger transaction (only possible with the triggers off) is reported on its own,
   * and moves the whole ledger's Σ DEBIT − Σ CREDIT by its amount; a second one-sided transaction
   * moves it back. Only this test's own change is asserted, never an absolute total, since other
   * tests' rows are in the shared ledger (doc 08 §5 "For step 12"); the total check is asserted to
   * fire exactly when the difference the test measured is not zero.
   */
  @Test
  void anUnbalancedTransactionAndAnUnbalancedLedgerAreLogged(CapturedOutput output)
      throws Exception {
    UUID suspense = systemAccountId("SYS-SUSPENSE");
    BigDecimal before = ledgerDifference();
    UUID debitOnly = oneSidedPosting(suspense, "DEBIT");
    UUID creditOnly = null;
    try {
      BigDecimal injected = ledgerDifference();
      assertThat(injected.subtract(before)).isEqualByComparingTo("0.01");

      Run run = run(output);

      assertThat(run.kinds(debitOnly))
          .containsExactlyInAnyOrder(Kind.POSTING_WITHOUT_MOVEMENT, Kind.TRANSACTION_UNBALANCED);
      assertThat(run.errorLine(Kind.TRANSACTION_UNBALANCED, debitOnly))
          .contains("check=SUM, kind=TRANSACTION_UNBALANCED, ledgerTransactionId=" + debitOnly);
      assertTotalCheckMatches(run, injected);
    } finally {
      creditOnly = oneSidedPosting(suspense, "CREDIT");
    }
    BigDecimal restoredDifference = ledgerDifference();
    assertThat(restoredDifference).isEqualByComparingTo(before);

    Run restored = run(output);

    assertTotalCheckMatches(restored, restoredDifference);
    assertThat(restored.kinds(debitOnly)).contains(Kind.TRANSACTION_UNBALANCED);
    assertThat(restored.kinds(creditOnly)).contains(Kind.TRANSACTION_UNBALANCED);
  }

  /** Σ DEBIT − Σ CREDIT over the whole ledger, read now. */
  private BigDecimal ledgerDifference() {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)"
            + " FROM ledger.ledger_entries",
        BigDecimal.class);
  }

  /** The total check fires, once and at ERROR, exactly when {@code difference} is not zero. */
  private static void assertTotalCheckMatches(Run run, BigDecimal difference) {
    boolean unbalanced = difference.signum() != 0;
    assertThat(run.report().mismatches())
        .filteredOn(m -> m.kind() == Kind.LEDGER_TOTAL_UNBALANCED)
        .hasSize(unbalanced ? 1 : 0);
    List<String> totalLines =
        run.lines().stream()
            .filter(
                l -> l.contains("RECONCILIATION_MISMATCH: check=SUM, kind=LEDGER_TOTAL_UNBALANCED"))
            .toList();
    assertThat(totalLines).hasSize(unbalanced ? 1 : 0);
    assertThat(totalLines).allSatisfy(l -> assertThat(l).contains("\"level\":\"ERROR\""));
  }

  /** One ledger transaction with a single 0.01 entry, committed with the triggers disabled. */
  private UUID oneSidedPosting(UUID account, String entryType) throws Exception {
    UUID id = UUID.randomUUID();
    withTriggersDisabled(
        superuser -> {
          update(
              superuser,
              "INSERT INTO ledger.financial_transactions"
                  + " (id, transaction_reference, transaction_type, currency, created_xid)"
                  + " VALUES (?, ?, 'DEPOSIT', 'THB', pg_current_xact_id())",
              id,
              "TST-" + UUID.randomUUID());
          update(
              superuser,
              "INSERT INTO ledger.ledger_entries"
                  + " (transaction_id, account_id, entry_type, amount, currency)"
                  + " VALUES (?, ?, ?, 0.01, 'THB')",
              id,
              account,
              entryType);
        });
    return id;
  }
}
