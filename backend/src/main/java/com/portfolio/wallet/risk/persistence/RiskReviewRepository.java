package com.portfolio.wallet.risk.persistence;

import java.sql.Types;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code risk.risk_reviews} (V5, V11; documentations/06 MVP-ADR-24 amendment "Review outcomes"):
 * append-only, one row per transfer ({@code uq_risk_reviews_transfer}). The database sets {@code
 * id} and {@code created_at}.
 */
@Repository
public class RiskReviewRepository {

  private final JdbcClient jdbc;

  public RiskReviewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts one review row. Nullable columns are passed as null.
   *
   * @throws DuplicateKeyException if the transfer already has a review row ({@code
   *     uq_risk_reviews_transfer}, the table's only unique key besides its generated id)
   */
  public void insert(
      UUID transferId,
      UUID assessmentId,
      String action,
      String decidedBy,
      String requestedAction,
      UUID actorUserId,
      String reasonCategory,
      String reason,
      String systemReason) {
    jdbc.sql(
            """
            INSERT INTO risk.risk_reviews
              (transfer_id, assessment_id, action, decided_by, requested_action, actor_user_id,
               reason_category, reason, system_reason)
            VALUES (:transfer, :assessment, :action, :decidedBy, :requested, :actor,
                    :category, :reason, :systemReason)
            """)
        .param("transfer", transferId)
        .param("assessment", assessmentId)
        .param("action", action)
        .param("decidedBy", decidedBy)
        .param("requested", requestedAction, Types.VARCHAR)
        .param("actor", actorUserId, Types.OTHER)
        .param("category", reasonCategory, Types.VARCHAR)
        .param("reason", reason, Types.VARCHAR)
        .param("systemReason", systemReason, Types.VARCHAR)
        .update();
  }
}
