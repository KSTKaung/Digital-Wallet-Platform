package com.portfolio.wallet.risk.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The risk module's read for the scheduled reconciliation (documentations/06 MVP-ADR-03 check 5,
 * MVP-ADR-24 "Review outcomes"). Read-only, without locks, risk schema only. Only the outcome, its
 * source and the assessment the row references are read: never the reviewer's text, the actor or
 * the times (check 5 compares outcomes and categories, never timestamps; MVP-ADR-24 "Staff
 * decisions", "Times"). Assessments and investigations are not read at all, so one without a
 * transfer is never flagged (Q4).
 */
@Repository
public class RiskReconciliationRepository {

  private final JdbcClient jdbc;

  public RiskReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A review row's id, transfer, outcome ({@code action}) and decision source. */
  public record ReviewOutcomeRow(
      UUID id, UUID transferId, UUID assessmentId, String action, String decidedBy) {}

  /** Every review row (full scan of {@code risk.risk_reviews}). */
  public List<ReviewOutcomeRow> reviewOutcomes() {
    return jdbc.sql(
            "SELECT id, transfer_id, assessment_id, action, decided_by FROM risk.risk_reviews")
        .query(
            (rs, row) ->
                new ReviewOutcomeRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("transfer_id", UUID.class),
                    rs.getObject("assessment_id", UUID.class),
                    rs.getString("action"),
                    rs.getString("decided_by")))
        .list();
  }
}
