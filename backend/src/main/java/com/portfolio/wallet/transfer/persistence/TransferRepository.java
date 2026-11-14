package com.portfolio.wallet.transfer.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code transfer.transfers} (V4, V11). Plain SQL with JdbcClient (MVP-ADR-10 amendment). The
 * database stamps {@code created_at}, {@code updated_at}, {@code held_at} and {@code decided_at}
 * (V4 triggers), and every write reads them back with {@code RETURNING}, so the application never
 * writes or assumes a time it did not get from the database (doc 08 §5, PR 2 review notes).
 *
 * <h2>Visibility (Q8)</h2>
 *
 * {@link #VISIBLE_TO_CALLER} is <b>the one place</b> of the customer visibility rule
 * (documentations/06 MVP-ADR-24 amendment "Visibility (Q8)"; doc 08 §4C.2 Q8): the sender sees
 * every status of their transfers, the receiver only {@code COMPLETED} ones. The list, the count,
 * the detail and the status endpoint all read through it ({@link #findVisible}, {@link
 * #pageVisible}, {@link #countVisible}); none of them has a rule of its own.
 *
 * <p>Nothing here logs; the description is a parameter or a column, never part of a message.
 */
@Repository
public class TransferRepository {

  /** Q8: the sender sees every status, the receiver only {@code COMPLETED}. */
  static final String VISIBLE_TO_CALLER =
      "(sender_customer_id = :caller OR (receiver_customer_id = :caller AND status = 'COMPLETED'))";

  private static final String COLUMNS =
      """
      id, reference, sender_customer_id, receiver_customer_id, sender_wallet_id,
      receiver_wallet_id, amount, currency, description, status, idempotency_key, limit_date,
      risk_assessment_id, held_at, review_expires_at, decided_at, rejection_reason_category,
      ledger_status, correlation_id, version, created_at, updated_at
      """;

  private static final String SELECT = "SELECT " + COLUMNS + " FROM transfer.transfers WHERE ";

  private final JdbcClient jdbc;

  public TransferRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A transfer to insert. The status decides the times the database sets: {@code held_at} only for
   * {@code PENDING_REVIEW} (V4 trigger), and {@code review_expires_at} is computed in the same
   * statement from the database's {@code now()}, so the deadline is exactly {@code held_at} plus
   * the configured review duration (MVP-ADR-24).
   *
   * @param ledgerStatus {@code POSTED} exactly for {@code COMPLETED} (V4), otherwise null
   */
  public record NewTransfer(
      UUID id,
      String reference,
      UUID senderCustomerId,
      UUID receiverCustomerId,
      UUID senderWalletId,
      UUID receiverWalletId,
      BigDecimal amount,
      String currency,
      String description,
      String status,
      UUID idempotencyKey,
      LocalDate limitDate,
      UUID riskAssessmentId,
      String ledgerStatus,
      String correlationId) {

    public NewTransfer {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(status, "status");
    }

    /** The description stays out of logs. */
    @Override
    public String toString() {
      return "NewTransfer[id=" + id + ", status=" + status + "]";
    }
  }

  /**
   * Inserts the transfer and returns the row as stored, with the database's times.
   *
   * @param reviewSeconds the review duration in whole seconds, used only for {@code PENDING_REVIEW}
   */
  public TransferRow insert(NewTransfer transfer, long reviewSeconds) {
    return jdbc.sql(
            """
            INSERT INTO transfer.transfers
              (id, reference, sender_customer_id, receiver_customer_id, sender_wallet_id,
               receiver_wallet_id, amount, currency, description, status, idempotency_key,
               limit_date, risk_assessment_id, review_expires_at, ledger_status, correlation_id)
            VALUES (:id, :reference, :sender, :receiver, :senderWallet, :receiverWallet, :amount,
                    :currency, :description, :status, :key, :limitDate, :assessment,
                    CASE WHEN CAST(:status AS text) = 'PENDING_REVIEW'
                         THEN now() + make_interval(secs => :reviewSeconds) END,
                    :ledgerStatus, :correlation)
            RETURNING
            """
                + COLUMNS)
        .param("id", transfer.id())
        .param("reference", transfer.reference())
        .param("sender", transfer.senderCustomerId())
        .param("receiver", transfer.receiverCustomerId())
        .param("senderWallet", transfer.senderWalletId())
        .param("receiverWallet", transfer.receiverWalletId())
        .param("amount", transfer.amount())
        .param("currency", transfer.currency())
        .param("description", transfer.description(), Types.VARCHAR)
        .param("status", transfer.status())
        .param("key", transfer.idempotencyKey())
        .param("limitDate", transfer.limitDate())
        .param("assessment", transfer.riskAssessmentId())
        .param("reviewSeconds", reviewSeconds)
        .param("ledgerStatus", transfer.ledgerStatus(), Types.VARCHAR)
        .param("correlation", transfer.correlationId(), Types.VARCHAR)
        .query(TransferRepository::map)
        .single();
  }

  /**
   * Whether a {@code COMPLETED} transfer from this sender to this receiver exists, the fact of the
   * new-recipient rule (MVP-ADR-24; doc 08 §5 step 3 notes; partial index {@code
   * ix_transfers_completed_sender_receiver}).
   */
  public boolean completedTransferExists(UUID senderCustomerId, UUID receiverCustomerId) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM transfer.transfers
                           WHERE sender_customer_id = :sender AND receiver_customer_id = :receiver
                             AND status = 'COMPLETED')
            """)
        .param("sender", senderCustomerId)
        .param("receiver", receiverCustomerId)
        .query(Boolean.class)
        .single();
  }

  /**
   * The transfer {@code sender} created with {@code idempotencyKey}, as committed now, without a
   * lock: the key's guard once its idempotency record has expired or been purged (doc 08 Q14,
   * applied to transfers by the owner on 2026-11-11). Reads through the unique constraint {@code
   * uq_transfers_sender_idempotency_key} (V4).
   */
  public Optional<TransferRow> findBySenderAndKey(UUID sender, UUID idempotencyKey) {
    return jdbc.sql(SELECT + "sender_customer_id = :sender AND idempotency_key = :key")
        .param("sender", sender)
        .param("key", idempotencyKey)
        .query(TransferRepository::map)
        .optional();
  }

  /**
   * A held transfer whose review deadline has passed: the expiry job's candidate, and its position
   * in the job's order.
   */
  public record DueTransfer(UUID id, Instant reviewExpiresAt) {

    public DueTransfer {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(reviewExpiresAt, "reviewExpiresAt");
    }
  }

  private static final String DUE =
      """
      SELECT id, review_expires_at FROM transfer.transfers
      WHERE status = 'PENDING_REVIEW' AND review_expires_at <= statement_timestamp()
      """;

  /**
   * The expiry job's candidates (documentations/06 MVP-ADR-24 "Expiry"): {@code PENDING_REVIEW}
   * transfers whose {@code review_expires_at} is at or before the database time of this statement,
   * the same clock as the V4 guard, soonest deadline first, then by id (partial index {@code
   * ix_transfers_review_expires}). Read without a lock: each candidate is locked and re-checked in
   * a transaction of its own ({@link #lockForUpdate} with {@link LockMode#SKIP_LOCKED}).
   *
   * @param after the last candidate of the previous page, or null for the first page; the page
   *     starts strictly after it in {@code (review_expires_at, id)} order, so one run never returns
   *     a transfer twice, also one it skipped or failed to expire
   * @param limit at least 1
   */
  public List<DueTransfer> findDueForExpiry(DueTransfer after, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1");
    }
    JdbcClient.StatementSpec statement;
    if (after == null) {
      statement = jdbc.sql(DUE + " ORDER BY review_expires_at, id LIMIT :limit");
    } else {
      statement =
          jdbc.sql(
                  DUE
                      + " AND (review_expires_at, id) > (:afterDeadline, :afterId)"
                      + " ORDER BY review_expires_at, id LIMIT :limit")
              .param("afterDeadline", Timestamps.bind(after.reviewExpiresAt()))
              .param("afterId", after.id());
    }
    return statement
        .param("limit", limit)
        .query(
            (rs, row) ->
                new DueTransfer(
                    rs.getObject("id", UUID.class), Timestamps.read(rs, "review_expires_at")))
        .list();
  }

  /**
   * A held transfer that has waited the reminder period and has not reached its deadline: the
   * reminder job's candidate (documentations/06 MVP-ADR-24 "Expiry"), with the three values its
   * email may state (Q9) and its position in the job's order. Never the parties or the description.
   */
  public record ReminderCandidate(
      UUID id, String reference, BigDecimal amount, String currency, Instant reviewExpiresAt) {

    public ReminderCandidate {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(reviewExpiresAt, "reviewExpiresAt");
    }
  }

  /**
   * Held, held at least the reminder period before this statement's database time, and before the
   * deadline (at the deadline the expiry job owns it, and "will expire" would no longer be true).
   * The partial index {@code ix_transfers_review_expires} (held rows only) supplies the deadline
   * bound and the order; the {@code held_at} condition is not in the index and is checked on each
   * row fetched from the table. With one review duration the qualifying holds come first in
   * deadline order, so the last page of a run also fetches younger holds and discards them.
   */
  private static final String AWAITING_REMINDER =
      """
      SELECT id, reference, amount, currency, review_expires_at FROM transfer.transfers
      WHERE status = 'PENDING_REVIEW'
        AND held_at <= statement_timestamp() - make_interval(secs => :reminderSeconds)
        AND review_expires_at > statement_timestamp()
      """;

  /**
   * The reminder job's candidates, read without locks, soonest deadline first.
   *
   * @param after the last candidate of the previous page, or null for the first page; the page
   *     starts strictly after it in {@code (review_expires_at, id)} order
   * @param reminderSeconds the reminder period in whole seconds
   * @param limit at least 1
   */
  public List<ReminderCandidate> findAwaitingReminder(
      ReminderCandidate after, long reminderSeconds, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1");
    }
    JdbcClient.StatementSpec statement;
    if (after == null) {
      statement = jdbc.sql(AWAITING_REMINDER + " ORDER BY review_expires_at, id LIMIT :limit");
    } else {
      statement =
          jdbc.sql(
                  AWAITING_REMINDER
                      + " AND (review_expires_at, id) > (:afterDeadline, :afterId)"
                      + " ORDER BY review_expires_at, id LIMIT :limit")
              .param("afterDeadline", Timestamps.bind(after.reviewExpiresAt()))
              .param("afterId", after.id());
    }
    return statement
        .param("reminderSeconds", reminderSeconds)
        .param("limit", limit)
        .query(TransferRepository::mapReminderCandidate)
        .list();
  }

  /**
   * One transfer, if it is still a reminder candidate at this statement's time; read without a
   * lock, so the reminder never waits on or blocks a decision.
   */
  public Optional<ReminderCandidate> findAwaitingReminder(UUID id, long reminderSeconds) {
    return jdbc.sql(AWAITING_REMINDER + " AND id = :id")
        .param("reminderSeconds", reminderSeconds)
        .param("id", id)
        .query(TransferRepository::mapReminderCandidate)
        .optional();
  }

  private static ReminderCandidate mapReminderCandidate(ResultSet rs, int row) throws SQLException {
    return new ReminderCandidate(
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        Timestamps.read(rs, "review_expires_at"));
  }

  /** How a transfer-row lock treats a row another transaction holds. */
  public enum LockMode {
    /** Wait for it, bounded by the D14 {@code lock_timeout}: cancel and staff review. */
    WAIT(" FOR UPDATE"),
    /**
     * Skip it, as if it were absent: the expiry job (step 6), so several instances never wait on
     * each other or on a review in progress (MVP-ADR-24 "Concurrency"; doc 08 §4C.4 criterion 12).
     */
    SKIP_LOCKED(" FOR UPDATE SKIP LOCKED");

    private final String clause;

    LockMode(String clause) {
      this.clause = clause;
    }
  }

  /**
   * Locks the transfer row {@code FOR UPDATE} in a statement of its own: the first lock of a
   * cancel, an expiry or a review (doc 08 §4C.3). The status {@code UPDATE} comes later, so the V4
   * deadline check sees the time after any lock wait (doc 08 §5).
   *
   * @return empty if there is no such transfer, or ({@link LockMode#SKIP_LOCKED}) if another
   *     transaction holds it
   */
  public Optional<TransferRow> lockForUpdate(UUID id, LockMode mode) {
    return jdbc.sql(SELECT + "id = :id" + mode.clause)
        .param("id", id)
        .query(TransferRepository::map)
        .optional();
  }

  /**
   * Locks the transfer row {@code FOR UPDATE} only if {@code sender} sent it: ownership is part of
   * the locking statement, so a missing transfer and another customer's take no lock at all (the
   * identical {@code 404} of the cancel, MVP-ADR-19).
   */
  public Optional<TransferRow> lockSentBy(UUID id, UUID sender) {
    return jdbc.sql(SELECT + "id = :id AND sender_customer_id = :sender" + LockMode.WAIT.clause)
        .param("id", id)
        .param("sender", sender)
        .query(TransferRepository::map)
        .optional();
  }

  /**
   * A transfer that was held for review ({@code held_at} is set exactly for one created {@code
   * PENDING_REVIEW}, and never changes; V4), whatever its status now: the staff review's view of a
   * transfer. A transfer created {@code COMPLETED} or {@code BLOCKED} never went to review and is
   * not found.
   */
  private static final String HELD_FOR_REVIEW = "id = :id AND held_at IS NOT NULL";

  /**
   * Locks a transfer that was held for review {@code FOR UPDATE} in a statement of its own: the
   * first lock of a staff approval or rejection (doc 08 §4C.3). A transfer that never went to
   * review takes no lock and is not found (the staff {@code 404}).
   */
  public Optional<TransferRow> lockHeldForReview(UUID id) {
    return jdbc.sql(SELECT + HELD_FOR_REVIEW + LockMode.WAIT.clause)
        .param("id", id)
        .query(TransferRepository::map)
        .optional();
  }

  /** A transfer that was held for review, as committed now, without a lock. */
  public Optional<TransferRow> findHeldForReview(UUID id) {
    return jdbc.sql(SELECT + HELD_FOR_REVIEW)
        .param("id", id)
        .query(TransferRepository::map)
        .optional();
  }

  /**
   * One page of the review queue (documentations/06 MVP-ADR-24 amendment "Review queue (Q12)"):
   * {@code PENDING_REVIEW} transfers, soonest deadline first, then by id, so pages never overlap
   * (partial index {@code ix_transfers_review_expires}).
   */
  public List<TransferRow> pageAwaitingReview(int limit, long offset) {
    return jdbc.sql(
            SELECT
                + "status = 'PENDING_REVIEW' ORDER BY review_expires_at, id LIMIT :limit OFFSET"
                + " :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(TransferRepository::map)
        .list();
  }

  /** How many transfers are {@code PENDING_REVIEW}. */
  public long countAwaitingReview() {
    return jdbc.sql("SELECT count(*) FROM transfer.transfers WHERE status = 'PENDING_REVIEW'")
        .query(Long.class)
        .single();
  }

  /**
   * The database time at the start of this statement, read after the row lock is held, so a
   * deadline check compares the same clock as the V4 guard ({@code statement_timestamp()}).
   */
  public Instant databaseNow() {
    return jdbc.sql("SELECT statement_timestamp()")
        .query((rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant())
        .single();
  }

  /**
   * Moves a held transfer to its decided status under the caller's row lock, with the optimistic
   * {@code version} check; the V4 guard checks the move and the deadline against this statement's
   * time and sets {@code decided_at}, which is read back.
   *
   * @return the stored row after the change; empty if the version is no longer {@code
   *     expectedVersion}
   * @throws org.springframework.dao.DataAccessException with SQL state {@code 23001} ({@code
   *     restrict_violation}) if the guard refuses the move, for example because the deadline passed
   *     after the caller's own check
   */
  public Optional<TransferRow> updateDecision(
      UUID id,
      String status,
      String rejectionReasonCategory,
      String ledgerStatus,
      long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE transfer.transfers
            SET status = :status, rejection_reason_category = :category,
                ledger_status = :ledgerStatus, version = version + 1
            WHERE id = :id AND version = :version
            RETURNING
            """
                + COLUMNS)
        .param("status", status)
        .param("category", rejectionReasonCategory, Types.VARCHAR)
        .param("ledgerStatus", ledgerStatus, Types.VARCHAR)
        .param("id", id)
        .param("version", expectedVersion)
        .query(TransferRepository::map)
        .optional();
  }

  /** The transfer if {@code caller} may see it ({@link #VISIBLE_TO_CALLER}), as committed now. */
  public Optional<TransferRow> findVisible(UUID caller, UUID id) {
    return jdbc.sql(SELECT + "id = :id AND " + VISIBLE_TO_CALLER)
        .param("id", id)
        .param("caller", caller)
        .query(TransferRepository::map)
        .optional();
  }

  /**
   * One page of the transfers {@code caller} may see ({@link #VISIBLE_TO_CALLER}), newest first,
   * with {@code id} as the tie-breaker so pages never overlap.
   */
  public List<TransferRow> pageVisible(UUID caller, int limit, long offset) {
    return jdbc.sql(
            SELECT
                + VISIBLE_TO_CALLER
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .param("caller", caller)
        .param("limit", limit)
        .param("offset", offset)
        .query(TransferRepository::map)
        .list();
  }

  /** How many transfers {@code caller} may see ({@link #VISIBLE_TO_CALLER}). */
  public long countVisible(UUID caller) {
    return jdbc.sql("SELECT count(*) FROM transfer.transfers WHERE " + VISIBLE_TO_CALLER)
        .param("caller", caller)
        .query(Long.class)
        .single();
  }

  private static TransferRow map(ResultSet rs, int row) throws SQLException {
    return new TransferRow(
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getObject("sender_customer_id", UUID.class),
        rs.getObject("receiver_customer_id", UUID.class),
        rs.getObject("sender_wallet_id", UUID.class),
        rs.getObject("receiver_wallet_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("description"),
        rs.getString("status"),
        rs.getObject("idempotency_key", UUID.class),
        rs.getObject("limit_date", LocalDate.class),
        rs.getObject("risk_assessment_id", UUID.class),
        Timestamps.read(rs, "held_at"),
        Timestamps.read(rs, "review_expires_at"),
        Timestamps.read(rs, "decided_at"),
        rs.getString("rejection_reason_category"),
        rs.getString("ledger_status"),
        rs.getString("correlation_id"),
        rs.getLong("version"),
        Timestamps.read(rs, "created_at"),
        Timestamps.read(rs, "updated_at"));
  }
}
