package com.portfolio.wallet.audit.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Insert-only access to {@code audit.audit_logs}. Plain SQL rather than a JPA entity: the table is
 * append-only, so nothing here needs an entity's state tracking, and the {@code inet} and {@code
 * jsonb} columns need only a cast. It joins the caller's transaction through the shared DataSource.
 */
@Repository
public class AuditLogRepository {

  private static final String INSERT =
      """
      INSERT INTO audit.audit_logs (actor_id, actor_type, action, resource, resource_id, result,
                                    client_ip, correlation_id, old_value, new_value)
      VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?, ?::jsonb, ?::jsonb)
      """;

  private final JdbcClient jdbc;

  public AuditLogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(AuditLogRow row) {
    jdbc.sql(INSERT)
        .params(
            row.actorId(),
            row.actorType(),
            row.action(),
            row.resource(),
            row.resourceId(),
            row.result(),
            row.clientIp(),
            row.correlationId(),
            row.oldValue(),
            row.newValue())
        .update();
  }
}
