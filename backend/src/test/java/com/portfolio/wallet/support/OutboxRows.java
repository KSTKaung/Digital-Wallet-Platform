package com.portfolio.wallet.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the email outbox ({@code notification.notifications}, V9) for one user, so a test asserts
 * only on its own rows: the outbox is shared by every test in the JVM and never cleaned up.
 */
public final class OutboxRows {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One stored email: its event id, kind, status and template values. */
  public record Row(UUID eventId, String kind, String status, Map<String, String> values) {}

  private OutboxRows() {}

  /** The user's outbox rows of {@code kinds}, oldest first; every kind if none is given. */
  public static List<Row> of(JdbcTemplate jdbc, UUID userId, String... kinds) {
    List<Row> rows =
        jdbc.query(
            "SELECT event_id, kind, status, payload::text AS payload"
                + " FROM notification.notifications WHERE user_id = ?"
                + " ORDER BY occurred_at, event_id",
            (rs, n) ->
                new Row(
                    rs.getObject("event_id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("status"),
                    values(rs.getString("payload"))),
            userId);
    if (kinds.length == 0) {
      return rows;
    }
    List<String> wanted = List.of(kinds);
    return rows.stream().filter(row -> wanted.contains(row.kind())).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> values(String payload) {
    return JSON.readValue(payload, Map.class);
  }
}
