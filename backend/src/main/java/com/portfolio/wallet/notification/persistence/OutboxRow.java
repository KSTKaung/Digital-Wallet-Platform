package com.portfolio.wallet.notification.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One claimed {@code notification.notifications} row: what the dispatcher needs to send it. The
 * kind is the stored name of an {@code EmailKind}.
 *
 * @param attempts the attempt count after this claim, which counted itself: the claim's fencing
 *     token (R1)
 * @param occurredAt the time of the change the email reports
 * @param values the stored template values; {@code null} if the stored payload is not an object of
 *     strings, which fails that attempt when it is sent instead of failing the claim (so one such
 *     row can never stop the dispatcher's runs)
 */
public record OutboxRow(
    UUID eventId,
    UUID userId,
    String kind,
    int attempts,
    Instant occurredAt,
    Map<String, String> values) {

  public OutboxRow {
    values = values == null ? null : Map.copyOf(values);
  }

  /** Ids and the kind only: the template values stay out of logs. */
  @Override
  public String toString() {
    return "OutboxRow[eventId=" + eventId + ", kind=" + kind + ", attempts=" + attempts + "]";
  }
}
