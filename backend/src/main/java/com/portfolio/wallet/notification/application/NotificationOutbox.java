package com.portfolio.wallet.notification.application;

import com.portfolio.wallet.notification.persistence.NotificationRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a business email in the outbox, inside the transaction of the change it reports
 * (documentations/06 MVP-ADR-25): the row commits or rolls back with that change, so a rolled-back
 * change sends nothing. {@link OutboxDispatcher} sends it after commit.
 *
 * <p>The row holds the recipient's user id, never an address, and only the template values its kind
 * declares, so no KYC content, secret or address can reach it. The database records the time of the
 * change, the transaction's {@code now()}, which equals the change's own database stamps.
 */
@Service
public class NotificationOutbox {

  private final NotificationRepository notifications;
  private final Clock clock;

  public NotificationOutbox(NotificationRepository notifications, Clock clock) {
    this.notifications = notifications;
    this.clock = clock;
  }

  /**
   * Must run inside the caller's business transaction; without one it throws.
   *
   * @param userId the recipient's auth user id
   * @param kind a business email kind (KYC decision, wallet freeze or unfreeze, money emails)
   * @param values the template values: exactly the keys the kind declares (none for the KYC and
   *     wallet status kinds; {@link MoneyEmailValues} for the money kinds)
   * @return the new row's event id, unique and stable
   * @throws IllegalArgumentException for a kind that is not sent through the outbox, or an
   *     undeclared, missing or null value
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID enqueue(UUID userId, EmailKind kind, Map<String, String> values) {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(values, "values");
    Set<String> accepted =
        OutboxTemplates.keys(kind)
            .orElseThrow(
                () -> new IllegalArgumentException(kind + " is not sent through the outbox"));
    values.forEach(
        (key, value) -> {
          if (!accepted.contains(key) || value == null) {
            throw new IllegalArgumentException(kind + " does not take the value " + key);
          }
        });
    // Every declared value, so a row can always be rendered when it is sent.
    if (!values.keySet().containsAll(accepted)) {
      throw new IllegalArgumentException(kind + " needs the values " + accepted);
    }
    UUID eventId = UUID.randomUUID();
    // Due at once, by the application clock that the dispatcher compares with (N2).
    notifications.insert(eventId, userId, kind.name(), Map.copyOf(values), clock.instant());
    return eventId;
  }

  /** {@link #enqueue(UUID, EmailKind, Map)} without template values. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID enqueue(UUID userId, EmailKind kind) {
    return enqueue(userId, kind, Map.of());
  }
}
