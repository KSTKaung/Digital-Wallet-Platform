package com.portfolio.wallet.risk.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code risk.sender_risk_state}, {@code risk.risk_assessments}, {@code risk.risk_rule_hits} and
 * {@code risk.investigations} (V5, V12; documentations/06 MVP-ADR-24). Assessments and rule hits
 * are append-only; investigations are only opened here (Q13).
 *
 * <p><b>Time.</b> The V5 trigger stamps {@code assessed_at} with {@code now()}, the start of the
 * assessment's transaction. The frequency window and the "now" against which the sender wallet's
 * age is measured are read with the same {@code now()} ({@link #countInWindow}), so both sides of
 * every comparison are database time from the same clock. An assessment's time is its transaction's
 * start, which comes before its sender-lock wait and before its own insert; a later assessment
 * therefore sees it in the window for the window length minus that lock wait and pre-insert time.
 * This edge skew is bounded by the lock wait (at most {@code lock_timeout}, doc 07 D14) and is an
 * accepted residual (step 3 review), not a guarantee that no assessment is ever missed at the
 * window's edge.
 */
@Repository
public class RiskAssessmentRepository {

  /** The database time of the transaction and the sender's assessments in the window before it. */
  public record WindowCount(Instant databaseNow, int assessments) {}

  private final JdbcClient jdbc;

  public RiskAssessmentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Creates the sender's lock row if it is absent and locks it until the transaction ends, in one
   * statement. A concurrent assessment of the same sender waits here (on the row lock, or on the
   * primary key while the row is being inserted by another transaction), so assessments of one
   * sender run one after another; other senders' rows are not touched.
   */
  public void lockSender(UUID customerId) {
    jdbc.sql(
            """
            INSERT INTO risk.sender_risk_state (customer_id) VALUES (:customer)
            ON CONFLICT (customer_id) DO UPDATE SET updated_at = now()
            """)
        .param("customer", customerId)
        .update();
  }

  /**
   * The transaction's {@code now()} and the sender's assessments with {@code assessed_at} after
   * {@code now() - window}, every decision included; an assessment exactly {@code window} old has
   * left the window. Uses {@code ix_risk_assessments_customer_assessed}.
   */
  public WindowCount countInWindow(UUID customerId, long windowSeconds) {
    return jdbc.sql(
            """
            SELECT now() AS database_now, count(*) AS assessments
            FROM risk.risk_assessments
            WHERE customer_id = :customer
              AND assessed_at > now() - make_interval(secs => :window)
            """)
        .param("customer", customerId)
        .param("window", windowSeconds)
        .query(
            (rs, row) ->
                new WindowCount(Timestamps.read(rs, "database_now"), rs.getInt("assessments")))
        .single();
  }

  /**
   * Inserts the assessment; the database sets {@code id} and {@code assessed_at}.
   *
   * @return the new assessment id
   */
  public UUID insertAssessment(
      UUID transferId,
      UUID customerId,
      BigDecimal amount,
      String currency,
      int score,
      String decision) {
    return jdbc.sql(
            """
            INSERT INTO risk.risk_assessments
              (transfer_id, customer_id, amount, currency, score, decision)
            VALUES (:transfer, :customer, :amount, :currency, :score, :decision)
            RETURNING id
            """)
        .param("transfer", transferId)
        .param("customer", customerId)
        .param("amount", amount)
        .param("currency", currency)
        .param("score", score)
        .param("decision", decision)
        .query(UUID.class)
        .single();
  }

  /** Inserts one triggered rule of the assessment. */
  public void insertRuleHit(UUID assessmentId, String ruleCode, int weight) {
    jdbc.sql(
            """
            INSERT INTO risk.risk_rule_hits (assessment_id, rule_code, weight)
            VALUES (:assessment, :code, :weight)
            """)
        .param("assessment", assessmentId)
        .param("code", ruleCode)
        .param("weight", weight)
        .update();
  }

  /** Opens an investigation of the assessment; the database sets {@code OPEN} and the time. */
  public void openInvestigation(UUID assessmentId) {
    jdbc.sql("INSERT INTO risk.investigations (assessment_id) VALUES (:assessment)")
        .param("assessment", assessmentId)
        .update();
  }
}
