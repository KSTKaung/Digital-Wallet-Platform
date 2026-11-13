package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.MoneyEmailValues;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The transfer emails (documentations/06 MVP-ADR-24 amendment "Emails (Q9)", MVP-ADR-25; doc 08
 * §4C.2 Q9, §4C.4 criterion 19), written as outbox rows inside the business transaction that made
 * the change, so they commit or roll back with it and are sent after the commit.
 *
 * <ul>
 *   <li>{@code COMPLETED}: {@code TRANSFER_SENT} to the sender and {@code TRANSFER_RECEIVED} to the
 *       receiver;
 *   <li>{@code PENDING_REVIEW}: {@code TRANSFER_HELD} to the sender only; the receiver hears
 *       nothing before completion;
 *   <li>{@code REJECTED} (a staff rejection, or an approval that found a wallet frozen or closed):
 *       {@code TRANSFER_REJECTED} to the sender only; never the reviewer's reason or the category;
 *   <li>{@code CANCELLED}: {@code TRANSFER_CANCELLED} to the sender;
 *   <li>{@code EXPIRED} (the job, or a cancel after the deadline): {@code TRANSFER_EXPIRED} to the
 *       sender.
 * </ul>
 *
 * Never for {@code BLOCKED} (no way to learn the risk rules), a stored refusal, a replay or a
 * {@code 409}: callers call this only on the path that wrote the new status. Each row holds the
 * recipient's auth user id (a customer id is the user id, {@code usr.customers.user_id}) and the
 * amount, currency and server reference only (MoneyEmailValues): never a balance, a name, a wallet
 * number or the description. No address is looked up here, so nothing waits on another module under
 * the money locks.
 */
@Component
class TransferEmails {

  private final NotificationOutbox outbox;

  TransferEmails(NotificationOutbox outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  /**
   * Writes the emails of {@code row}'s status, as stored by this transaction.
   *
   * @throws IllegalStateException for {@code BLOCKED}, which never sends an email (Q9)
   */
  void statusChanged(TransferRow row) {
    Objects.requireNonNull(row, "row");
    switch (TransferStatus.valueOf(row.status())) {
      case COMPLETED -> {
        send(row.senderCustomerId(), EmailKind.TRANSFER_SENT, row);
        send(row.receiverCustomerId(), EmailKind.TRANSFER_RECEIVED, row);
      }
      case PENDING_REVIEW -> send(row.senderCustomerId(), EmailKind.TRANSFER_HELD, row);
      case CANCELLED -> send(row.senderCustomerId(), EmailKind.TRANSFER_CANCELLED, row);
      case EXPIRED -> send(row.senderCustomerId(), EmailKind.TRANSFER_EXPIRED, row);
      case REJECTED -> send(row.senderCustomerId(), EmailKind.TRANSFER_REJECTED, row);
      case BLOCKED ->
          throw new IllegalStateException("no transfer email for status " + row.status());
    }
  }

  private void send(UUID userId, EmailKind kind, TransferRow row) {
    outbox.enqueue(
        userId, kind, MoneyEmailValues.of(row.amount(), row.currency(), row.reference()));
  }
}
