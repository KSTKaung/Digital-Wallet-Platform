package com.portfolio.wallet.notification.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * One claimed {@code notification.notifications} row: what the dispatcher needs to send it. The
 * kind is the stored name of an {@code EmailKind}.
 *
 * @param attempts the attempt count after this claim, which counted itself: the claim's fencing
 *     token (R1)
 * @param occurredAt the time of the change the email reports
 */
public record OutboxRow(UUID eventId, UUID userId, String kind, int attempts, Instant occurredAt) {}
