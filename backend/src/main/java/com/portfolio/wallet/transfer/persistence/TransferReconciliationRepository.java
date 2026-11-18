package com.portfolio.wallet.transfer.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The transfer module's reads for the scheduled reconciliation (documentations/06 MVP-ADR-03 checks
 * 2–5, MVP-ADR-22 Y5, MVP-ADR-24 "Review outcomes"; doc 07 §5). Read-only, without locks (a lock
 * would make the reconciliation wait on review decisions and the expiry job), transfer schema only.
 */
@Repository
public class TransferReconciliationRepository {

  private final JdbcClient jdbc;

  public TransferReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A completed transfer: its id, posting reference, amount and both wallets. */
  public record CompletedRow(
      UUID id, String reference, BigDecimal amount, UUID senderWalletId, UUID receiverWalletId) {}

  /**
   * The amount held by {@code PENDING_REVIEW} transfers of one sender wallet to one recipient
   * wallet on one stored day.
   */
  public record HeldSumRow(
      UUID senderWalletId, UUID receiverWalletId, LocalDate limitDate, BigDecimal amount) {}

  /**
   * A transfer that entered {@code PENDING_REVIEW}: its status now, its customer category and the
   * risk assessment it references.
   */
  public record HeldOutcomeRow(
      UUID id, String status, String rejectionReasonCategory, UUID riskAssessmentId) {}

  /** Every {@code COMPLETED} transfer, held first or not. */
  public List<CompletedRow> completed() {
    return jdbc.sql(
            """
            SELECT id, reference, amount, sender_wallet_id, receiver_wallet_id
            FROM transfer.transfers WHERE status = 'COMPLETED'
            """)
        .query(
            (rs, row) ->
                new CompletedRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("reference"),
                    rs.getBigDecimal("amount"),
                    rs.getObject("sender_wallet_id", UUID.class),
                    rs.getObject("receiver_wallet_id", UUID.class)))
        .list();
  }

  /**
   * Σ amount of the {@code PENDING_REVIEW} transfers per sender wallet, recipient wallet and
   * <b>stored</b> {@code limit_date} (the day the hold reserved; MVP-ADR-22 Y5), never a day
   * recomputed from a time.
   */
  public List<HeldSumRow> heldSums() {
    return jdbc.sql(
            """
            SELECT sender_wallet_id, receiver_wallet_id, limit_date, sum(amount) AS amount
            FROM transfer.transfers WHERE status = 'PENDING_REVIEW'
            GROUP BY sender_wallet_id, receiver_wallet_id, limit_date
            """)
        .query(
            (rs, row) ->
                new HeldSumRow(
                    rs.getObject("sender_wallet_id", UUID.class),
                    rs.getObject("receiver_wallet_id", UUID.class),
                    rs.getObject("limit_date", LocalDate.class),
                    rs.getBigDecimal("amount")))
        .list();
  }

  /**
   * Every transfer that was ever held. The insert trigger (V4) sets {@code held_at} exactly for a
   * row inserted as {@code PENDING_REVIEW}, and the update guard keeps it, so {@code held_at IS NOT
   * NULL} means "entered review"; {@code COMPLETED} and {@code BLOCKED} rows created directly have
   * none.
   */
  public List<HeldOutcomeRow> everHeld() {
    return jdbc.sql(
            """
            SELECT id, status, rejection_reason_category, risk_assessment_id
            FROM transfer.transfers WHERE held_at IS NOT NULL
            """)
        .query(
            (rs, row) ->
                new HeldOutcomeRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getString("rejection_reason_category"),
                    rs.getObject("risk_assessment_id", UUID.class)))
        .list();
  }
}
