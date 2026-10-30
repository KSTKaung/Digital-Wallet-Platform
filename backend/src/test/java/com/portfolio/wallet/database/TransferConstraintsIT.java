package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.NOT_NULL_VIOLATION;
import static com.portfolio.wallet.support.SqlAssertions.assertCheckViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertRestrictViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertSqlState;
import static com.portfolio.wallet.support.SqlAssertions.assertUniqueViolation;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Transfer constraints and the update guard (documentations/05 §4; doc 06 MVP-ADR-02 "Transfers",
 * MVP-ADR-03, -24; acceptance criterion 6, test case 6). Every statement runs as {@code
 * wallet_app}.
 */
class TransferConstraintsIT extends DatabaseIntegrationTest {

  private static final String TRANSFERS = "transfer.transfers";

  // Insert checks -----------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"COMPLETED", "PENDING_REVIEW", "REJECTED", "CANCELLED", "EXPIRED", "BLOCKED"})
  void everyDocumentedStatusIsAccepted(String status) throws SQLException {
    UUID transfer = transferInStatus(status);

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM transfer.transfers WHERE id = ?", String.class, transfer))
        .isEqualTo(status);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"PENDING", "PROCESSING", "FAILED", "completed"})
  void unknownStatusIsRejected(String status) {
    assertCheckViolation(
        "ck_transfers_status",
        () -> insert(TRANSFERS, with(validTransfer("BLOCKED"), "status", status)));
  }

  @Test
  void transferToTheSameCustomerIsRejected() {
    Map<String, Object> transfer = validTransfer("COMPLETED");

    assertCheckViolation(
        "ck_transfers_not_self_customer",
        () ->
            insert(
                TRANSFERS,
                with(transfer, "receiver_customer_id", transfer.get("sender_customer_id"))));
  }

  @Test
  void transferToTheSameWalletIsRejected() {
    Map<String, Object> transfer = validTransfer("COMPLETED");

    assertCheckViolation(
        "ck_transfers_not_self_wallet",
        () ->
            insert(
                TRANSFERS, with(transfer, "receiver_wallet_id", transfer.get("sender_wallet_id"))));
  }

  static Stream<Arguments> invalidColumns() {
    return Stream.of(
        Arguments.of("amount", new BigDecimal("0.00"), "ck_transfers_amount"),
        Arguments.of("amount", new BigDecimal("-0.01"), "ck_transfers_amount"),
        Arguments.of("currency", "USD", "ck_transfers_currency"),
        Arguments.of("ledger_status", "PENDING_POST", "ck_transfers_ledger_status"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidColumns")
  void transferChecksRejectInvalidValues(String column, Object value, String constraint) {
    assertCheckViolation(
        constraint, () -> insert(TRANSFERS, with(validTransfer("COMPLETED"), column, value)));
  }

  @Test
  void completedTransferRequiresPostedLedgerStatus() {
    assertCheckViolation(
        "ck_transfers_completed_posted",
        () -> insert(TRANSFERS, with(validTransfer("COMPLETED"), "ledger_status", null)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"PENDING_REVIEW", "BLOCKED"})
  void onlyACompletedTransferMayBePosted(String status) {
    assertCheckViolation(
        "ck_transfers_completed_posted",
        () -> insert(TRANSFERS, with(validTransfer(status), "ledger_status", "POSTED")));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"REJECTED", "CANCELLED", "EXPIRED"})
  void transferLeavingReviewUnapprovedCannotBePosted(String status) throws SQLException {
    UUID transfer =
        "EXPIRED".equals(status) ? newOverdueHeldTransfer() : newTransfer("PENDING_REVIEW");
    String category = "REJECTED".equals(status) ? "'REVIEW'" : "NULL";

    assertCheckViolation(
        "ck_transfers_completed_posted",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = ?, ledger_status = 'POSTED',"
                    + " rejection_reason_category = "
                    + category
                    + " WHERE id = ?",
                status,
                transfer));
  }

  // The insert trigger clears decided_at, so even a client-supplied decision time cannot help.
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"REJECTED", "CANCELLED", "EXPIRED"})
  void transferCannotBeInsertedAsAlreadyDecided(String status) {
    Map<String, Object> decided =
        with(
            with(validTransfer(status), "decided_at", OffsetDateTime.now(ZoneOffset.UTC)),
            "rejection_reason_category",
            "REJECTED".equals(status) ? "REVIEW" : null);

    assertCheckViolation("ck_transfers_left_review_decided", () -> insert(TRANSFERS, decided));
  }

  @Test
  void rejectingWithoutACategoryIsRefused() throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertCheckViolation(
        "ck_transfers_rejected_category",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'REJECTED' WHERE id = ?", transfer));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"COMPLETED", "BLOCKED"})
  void transferCreatedWithoutReviewCannotHaveACategory(String status) {
    assertCheckViolation(
        "ck_transfers_rejected_category",
        () ->
            insert(TRANSFERS, with(validTransfer(status), "rejection_reason_category", "REVIEW")));
  }

  @Test
  void approvingWithACategoryIsRefused() throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertCheckViolation(
        "ck_transfers_rejected_category",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'COMPLETED', ledger_status = 'POSTED',"
                    + " rejection_reason_category = 'REVIEW' WHERE id = ?",
                transfer));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"CANCELLED", "EXPIRED"})
  void cancellingOrExpiringWithACategoryIsRefused(String status) throws SQLException {
    UUID transfer =
        "EXPIRED".equals(status) ? newOverdueHeldTransfer() : newTransfer("PENDING_REVIEW");

    assertCheckViolation(
        "ck_transfers_rejected_category",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = ?, rejection_reason_category = 'REVIEW'"
                    + " WHERE id = ?",
                status,
                transfer));
  }

  @Test
  void pendingReviewRequiresADeadline() {
    assertCheckViolation(
        "ck_transfers_pending_review_hold",
        () -> insert(TRANSFERS, with(validTransfer("PENDING_REVIEW"), "review_expires_at", null)));
  }

  @Test
  void pendingReviewWithoutHoldTimeFailsTheCheckOnceTheTriggerIsOutOfTheWay() {
    assertCheckViolation(
        "ck_transfers_pending_review_hold",
        () -> insertWithTriggersDisabled(TRANSFERS, validTransfer("PENDING_REVIEW")));
  }

  @Test
  void sameSenderCannotReuseAnIdempotencyKey() throws SQLException {
    Map<String, Object> first = validTransfer("COMPLETED");
    insert(TRANSFERS, first);

    Map<String, Object> second =
        with(
            with(validTransfer("COMPLETED"), "sender_customer_id", first.get("sender_customer_id")),
            "idempotency_key",
            first.get("idempotency_key"));

    assertUniqueViolation("uq_transfers_sender_idempotency_key", () -> insert(TRANSFERS, second));
  }

  @Test
  void differentSendersMayUseTheSameIdempotencyKey() throws SQLException {
    UUID key = UUID.randomUUID();

    insert(TRANSFERS, with(validTransfer("COMPLETED"), "idempotency_key", key));
    insert(TRANSFERS, with(validTransfer("COMPLETED"), "idempotency_key", key));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT sender_customer_id) FROM transfer.transfers"
                    + " WHERE idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(2);
  }

  @Test
  void referenceIsUnique() throws SQLException {
    Map<String, Object> first = validTransfer("COMPLETED");
    insert(TRANSFERS, first);

    assertUniqueViolation(
        "uq_transfers_reference",
        () ->
            insert(
                TRANSFERS, with(validTransfer("COMPLETED"), "reference", first.get("reference"))));
  }

  // Update guard: PENDING_REVIEW is the only state that may change -----------------------------

  @Test
  void pendingReviewCanBeCompletedWithItsPosting() throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertThat(
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'COMPLETED', ledger_status = 'POSTED',"
                    + " decided_at = now(), version = version + 1 WHERE id = ?",
                transfer))
        .isEqualTo(1);
  }

  @Test
  void pendingReviewCanBeRejectedWithACategory() throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertThat(
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'REJECTED', decided_at = now(),"
                    + " rejection_reason_category = 'REVIEW', version = version + 1"
                    + " WHERE id = ?",
                transfer))
        .isEqualTo(1);
  }

  @Test
  void pendingReviewCanBeCancelled() throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertThat(
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'CANCELLED', decided_at = now(),"
                    + " version = version + 1 WHERE id = ?",
                transfer))
        .isEqualTo(1);
  }

  // Review deadline: decisions before it, expiry at or after it (same boundary as Transfer) -----

  /**
   * A committed held transfer with the given deadline, held one day before it. {@code wallet_app}
   * cannot create one with a past deadline ({@code held_at} is {@code now()}), so this goes through
   * the test-only trigger bypass.
   */
  private UUID heldTransferWithDeadlineBypassingTriggers(OffsetDateTime deadline)
      throws SQLException {
    Map<String, Object> transfer =
        with(
            with(validTransfer("PENDING_REVIEW"), "held_at", deadline.minusDays(1)),
            "review_expires_at",
            deadline);
    insertWithTriggersDisabled(TRANSFERS, transfer);
    return (UUID) transfer.get("id");
  }

  /** A held transfer whose deadline has passed: held two days ago, expired one day ago. */
  private UUID newOverdueHeldTransfer() throws SQLException {
    return heldTransferWithDeadlineBypassingTriggers(
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
  }

  /**
   * A committed transfer in {@code status}. REJECTED, CANCELLED and EXPIRED rows cannot be inserted
   * directly, so they take the real transition out of review.
   */
  private UUID transferInStatus(String status) throws SQLException {
    switch (status) {
      case "REJECTED", "CANCELLED" -> {
        UUID transfer = newTransfer("PENDING_REVIEW");
        jdbc.update(
            "UPDATE transfer.transfers SET status = ?, rejection_reason_category = ? WHERE id = ?",
            status,
            "REJECTED".equals(status) ? "REVIEW" : null,
            transfer);
        return transfer;
      }
      case "EXPIRED" -> {
        UUID transfer = newOverdueHeldTransfer();
        jdbc.update("UPDATE transfer.transfers SET status = 'EXPIRED' WHERE id = ?", transfer);
        return transfer;
      }
      default -> {
        return newTransfer(status);
      }
    }
  }

  @Test
  void walletAppCannotInsertAnOverdueHold() {
    OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

    assertCheckViolation(
        "ck_transfers_review_window",
        () ->
            insert(
                TRANSFERS,
                with(
                    with(validTransfer("PENDING_REVIEW"), "held_at", past.minusDays(1)),
                    "review_expires_at",
                    past)));
  }

  @Test
  void overdueHeldTransferCanExpire() throws SQLException {
    UUID transfer = newOverdueHeldTransfer();

    assertThat(
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'EXPIRED', decided_at = now()"
                    + " WHERE id = ?",
                transfer))
        .isEqualTo(1);
  }

  @Test
  void heldTransferCannotExpireBeforeItsDeadline() throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertRestrictViolation(
        "review has not expired yet",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'EXPIRED', decided_at = now()"
                    + " WHERE id = ?",
                transfer));
  }

  @ParameterizedTest(name = "to {0}")
  @ValueSource(strings = {"REJECTED", "CANCELLED"})
  void overdueHeldTransferCannotBeDecided(String status) throws SQLException {
    UUID transfer = newOverdueHeldTransfer();

    assertRestrictViolation(
        "review has expired; it cannot move to " + status,
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = ?, decided_at = now() WHERE id = ?",
                status,
                transfer));
  }

  @Test
  void overdueHeldTransferCannotBeCompleted() throws SQLException {
    UUID transfer = newOverdueHeldTransfer();

    assertRestrictViolation(
        "review has expired; it cannot move to COMPLETED",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'COMPLETED', ledger_status = 'POSTED',"
                    + " decided_at = now() WHERE id = ?",
                transfer));
  }

  /**
   * One statement that holds a transfer with {@code review_expires_at = statement_timestamp()} and
   * moves it to {@code status}. statement_timestamp() is the same throughout a DO block, including
   * in its triggers, so the guard sees the deadline exactly.
   */
  private static String holdAndLeaveReviewAtExactlyTheDeadline(UUID transfer, String status) {
    return """
        DO $$
        BEGIN
          INSERT INTO transfer.transfers (id, reference, sender_customer_id, receiver_customer_id,
            sender_wallet_id, receiver_wallet_id, amount, currency, status, idempotency_key,
            limit_date, risk_assessment_id, review_expires_at)
          VALUES ('%1$s', 'TRF-%1$s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
            gen_random_uuid(), 100.00, 'THB', 'PENDING_REVIEW', gen_random_uuid(), current_date,
            gen_random_uuid(), statement_timestamp());
          UPDATE transfer.transfers SET status = '%2$s' WHERE id = '%1$s';
        END
        $$
        """
        .formatted(transfer, status);
  }

  @Test
  void heldTransferIsExpiredAtExactlyItsDeadline() throws SQLException {
    UUID transfer = UUID.randomUUID();

    try (Connection c = openTransaction()) {
      // Moves statement_timestamp() past the transaction's now(), which becomes held_at.
      queryOne(c, "SELECT 1 FROM pg_sleep(0.01)", Integer.class);

      update(c, holdAndLeaveReviewAtExactlyTheDeadline(transfer, "EXPIRED"));

      assertThat(
              queryOne(
                  c,
                  "SELECT status = 'EXPIRED' AND decided_at = review_expires_at"
                      + " FROM transfer.transfers WHERE id = ?",
                  Boolean.class,
                  transfer))
          .isTrue();
    }
  }

  @Test
  void heldTransferCannotBeDecidedAtExactlyItsDeadline() throws SQLException {
    try (Connection c = openTransaction()) {
      queryOne(c, "SELECT 1 FROM pg_sleep(0.01)", Integer.class);

      assertRestrictViolation(
          "review has expired",
          () -> update(c, holdAndLeaveReviewAtExactlyTheDeadline(UUID.randomUUID(), "CANCELLED")));
    }
  }

  @Test
  void transactionThatBeganBeforeTheDeadlineCannotDecideButCanExpireAfterIt() throws SQLException {
    OffsetDateTime deadline =
        jdbc.queryForObject(
            "SELECT clock_timestamp() + interval '1500 milliseconds'", OffsetDateTime.class);
    Map<String, Object> held = with(validTransfer("PENDING_REVIEW"), "review_expires_at", deadline);
    insert(TRANSFERS, held);
    Object transfer = held.get("id");
    String complete =
        "UPDATE transfer.transfers SET status = 'COMPLETED', ledger_status = 'POSTED' WHERE id = ?";
    String reject =
        "UPDATE transfer.transfers SET status = 'REJECTED', rejection_reason_category = 'REVIEW'"
            + " WHERE id = ?";
    String cancel = "UPDATE transfer.transfers SET status = 'CANCELLED' WHERE id = ?";
    String expire = "UPDATE transfer.transfers SET status = 'EXPIRED' WHERE id = ?";

    try (Connection c = openTransaction()) {
      assertThat(transactionNow(c)).as("transaction began before the deadline").isBefore(deadline);
      // Control: before the deadline the same transaction may decide but not expire (both undone
      // by the savepoint).
      Savepoint beforeDeadline = c.setSavepoint();
      assertThat(update(c, cancel, transfer)).isEqualTo(1);
      c.rollback(beforeDeadline);
      assertRestrictViolation("review has not expired yet", () -> update(c, expire, transfer));
      c.rollback(beforeDeadline);

      queryOne(c, "SELECT 1 FROM pg_sleep(1.6)", Integer.class);

      for (String decision : new String[] {complete, reject, cancel}) {
        Savepoint savepoint = c.setSavepoint();
        assertRestrictViolation("review has expired", () -> update(c, decision, transfer));
        c.rollback(savepoint);
      }
      // now() is still before the deadline, but both directions compare statement_timestamp(), so
      // the transaction that found the deadline passed can expire the transfer (MVP-ADR-24).
      assertThat(transactionNow(c)).as("now() is fixed for the transaction").isBefore(deadline);
      assertThat(update(c, expire, transfer)).isEqualTo(1);
      assertThat(
              queryOne(
                  c,
                  "SELECT decided_at FROM transfer.transfers WHERE id = ?",
                  OffsetDateTime.class,
                  transfer))
          .isAfterOrEqualTo(deadline);
    }
  }

  @ParameterizedTest(name = "deadline {0} s after the hold")
  @ValueSource(longs = {0, -1})
  void reviewDeadlineMustBeAfterTheHold(long seconds) throws SQLException {
    try (Connection c = openTransaction()) {
      // held_at is the transaction's now(), so the boundary can be hit exactly.
      OffsetDateTime deadline = transactionNow(c).plusSeconds(seconds);

      assertCheckViolation(
          "ck_transfers_review_window",
          () ->
              insert(
                  c,
                  TRANSFERS,
                  with(validTransfer("PENDING_REVIEW"), "review_expires_at", deadline)));
    }
  }

  @Test
  void transferThatWasNeverHeldHasNoReviewDeadline() {
    assertCheckViolation(
        "ck_transfers_review_window",
        () ->
            insert(
                TRANSFERS,
                with(
                    validTransfer("COMPLETED"),
                    "review_expires_at",
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))));
  }

  static Stream<Arguments> decisionColumns() {
    return Stream.of(
        Arguments.of("decided_at", OffsetDateTime.now(ZoneOffset.UTC)),
        Arguments.of("rejection_reason_category", "REVIEW"));
  }

  @Test
  void heldTransferHasNoRejectionReasonOnInsert() {
    assertCheckViolation(
        "ck_transfers_pending_review_undecided",
        () ->
            insert(
                TRANSFERS,
                with(validTransfer("PENDING_REVIEW"), "rejection_reason_category", "REVIEW")));
  }

  // Timestamps set by the database at insert and when a transfer leaves review -----------------

  @Test
  void clientSuppliedTimestampsOfAHeldTransferAreOverwritten() throws SQLException {
    Map<String, Object> transfer = validTransfer("PENDING_REVIEW");
    for (String column : new String[] {"held_at", "updated_at", "decided_at"}) {
      transfer = with(transfer, column, BACKDATED);
    }
    Map<String, Object> held = transfer;
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, TRANSFERS, held);
        });

    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT held_at = ? AS held, updated_at = ? AS updated, decided_at IS NULL AS undecided"
                + " FROM transfer.transfers WHERE id = ?",
            now[0],
            now[0],
            held.get("id"));
    assertThat(stored.get("held")).isEqualTo(true);
    assertThat(stored.get("updated")).isEqualTo(true);
    assertThat(stored.get("undecided")).isEqualTo(true);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"COMPLETED", "BLOCKED"})
  void transferNotHeldStoresNoHoldOrDecisionTime(String status) throws SQLException {
    Map<String, Object> transfer =
        with(with(validTransfer(status), "held_at", BACKDATED), "decided_at", BACKDATED);

    insert(TRANSFERS, transfer);

    assertThat(
            jdbc.queryForObject(
                "SELECT held_at IS NULL AND decided_at IS NULL FROM transfer.transfers"
                    + " WHERE id = ?",
                Boolean.class,
                transfer.get("id")))
        .isTrue();
  }

  static Stream<Arguments> leavingReview() {
    return Stream.of(
        Arguments.of("COMPLETED", "status = 'COMPLETED', ledger_status = 'POSTED'"),
        Arguments.of("REJECTED", "status = 'REJECTED', rejection_reason_category = 'REVIEW'"),
        Arguments.of("CANCELLED", "status = 'CANCELLED'"));
  }

  @ParameterizedTest(name = "to {0}")
  @MethodSource("leavingReview")
  void leavingReviewOverwritesClientSuppliedDecidedAt(String status, String assignments)
      throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertDecidedAtIsTheUpdateStatementsTime(transfer, assignments);
  }

  @Test
  void expiringOverwritesClientSuppliedDecidedAt() throws SQLException {
    assertDecidedAtIsTheUpdateStatementsTime(newOverdueHeldTransfer(), "status = 'EXPIRED'");
  }

  private void assertDecidedAtIsTheUpdateStatementsTime(UUID transfer, String assignments)
      throws SQLException {
    try (Connection c = openTransaction()) {
      OffsetDateTime transactionStart = transactionNow(c);
      queryOne(c, "SELECT 1 FROM pg_sleep(0.05)", Integer.class);

      update(
          c,
          "UPDATE transfer.transfers SET " + assignments + ", decided_at = ? WHERE id = ?",
          BACKDATED,
          transfer);

      // After the transaction's now(), so the statement's time; not the client's value.
      assertThat(
              queryOne(
                  c,
                  "SELECT decided_at FROM transfer.transfers WHERE id = ?",
                  OffsetDateTime.class,
                  transfer))
          .isAfter(transactionStart);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("decisionColumns")
  void heldTransferCannotRecordADecisionWhileStillHeld(String column, Object value)
      throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertCheckViolation(
        "ck_transfers_pending_review_undecided",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET " + column + " = ? WHERE id = ?", value, transfer));
  }

  // Server-set and required columns -----------------------------------------------------------

  @Test
  void clientSuppliedCreatedAtIsOverwritten() throws SQLException {
    Map<String, Object> transfer = with(validTransfer("COMPLETED"), "created_at", BACKDATED);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, TRANSFERS, transfer);
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT created_at FROM transfer.transfers WHERE id = ?",
                OffsetDateTime.class,
                transfer.get("id")))
        .isEqualTo(now[0]);
  }

  @Test
  void riskAssessmentIsRequired() {
    assertSqlState(
        NOT_NULL_VIOLATION,
        () -> insert(TRANSFERS, with(validTransfer("COMPLETED"), "risk_assessment_id", null)));
  }

  @Test
  void pendingReviewCompletedWithoutPostingIsRejected() throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertCheckViolation(
        "ck_transfers_completed_posted",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'COMPLETED' WHERE id = ?", transfer));
  }

  @Test
  void pendingReviewCannotMoveToBlocked() throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertRestrictViolation(
        "cannot move from PENDING_REVIEW to BLOCKED",
        () ->
            jdbc.update("UPDATE transfer.transfers SET status = 'BLOCKED' WHERE id = ?", transfer));
  }

  static Stream<Arguments> protectedColumnChanges() {
    return Stream.of(
        Arguments.of("amount", new BigDecimal("1.00")),
        Arguments.of("currency", "USD"),
        Arguments.of("sender_customer_id", UUID.randomUUID()),
        Arguments.of("receiver_customer_id", UUID.randomUUID()),
        Arguments.of("sender_wallet_id", UUID.randomUUID()),
        Arguments.of("receiver_wallet_id", UUID.randomUUID()),
        Arguments.of("description", "changed"),
        Arguments.of("idempotency_key", UUID.randomUUID()),
        Arguments.of("reference", "TRF-" + UUID.randomUUID()),
        Arguments.of("limit_date", LocalDate.of(2001, 1, 1)),
        Arguments.of("risk_assessment_id", UUID.randomUUID()),
        Arguments.of("held_at", OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
        Arguments.of(
            "review_expires_at", OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
        Arguments.of("correlation_id", "changed"),
        Arguments.of("created_at", OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("protectedColumnChanges")
  void pendingReviewCannotChangeItsTerms(String column, Object value) throws SQLException {
    UUID transfer = newTransfer("PENDING_REVIEW");

    assertRestrictViolation(
        "may only change its status and decision fields",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET " + column + " = ? WHERE id = ?", value, transfer));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"COMPLETED", "REJECTED", "CANCELLED", "EXPIRED", "BLOCKED"})
  void finalTransferCannotChangeItsStatus(String status) throws SQLException {
    UUID transfer = transferInStatus(status);

    assertRestrictViolation(
        "is " + status + " and can no longer change",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET status = 'PENDING_REVIEW', held_at = now(),"
                    + " review_expires_at = now() + interval '1 day', ledger_status = NULL"
                    + " WHERE id = ?",
                transfer));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"COMPLETED", "REJECTED", "CANCELLED", "EXPIRED", "BLOCKED"})
  void finalTransferCannotChangeEvenADecisionField(String status) throws SQLException {
    UUID transfer = transferInStatus(status);

    assertRestrictViolation(
        "can no longer change",
        () ->
            jdbc.update(
                "UPDATE transfer.transfers SET version = version + 1, decided_at = now()"
                    + " WHERE id = ?",
                transfer));
  }

  @Test
  void updateRefreshesUpdatedAtEvenIfTheClientSetsIt() throws SQLException {
    OffsetDateTime backdated = OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    Map<String, Object> transfer = with(validTransfer("PENDING_REVIEW"), "updated_at", backdated);
    insert(TRANSFERS, transfer);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          update(
              c,
              "UPDATE transfer.transfers SET status = 'REJECTED',"
                  + " rejection_reason_category = 'REVIEW', updated_at = ? WHERE id = ?",
              backdated,
              transfer.get("id"));
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at FROM transfer.transfers WHERE id = ?",
                OffsetDateTime.class,
                transfer.get("id")))
        .isEqualTo(now[0]);
  }
}
