package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which transfer status emails whom (documentations/06 MVP-ADR-24 amendment "Emails (Q9)"; doc 08
 * §4C.4 criterion 19): the receiver only on completion, nothing for a block, and only the amount,
 * the currency and the reference.
 */
class TransferEmailsTest {

  private static final UUID SENDER = UUID.randomUUID();
  private static final UUID RECEIVER = UUID.randomUUID();
  private static final String REFERENCE = "TRF-0123456789ABCDEF0123456789ABCDEF";
  private static final Map<String, String> VALUES =
      Map.of("amount", "1300.50", "currency", "THB", "reference", REFERENCE);

  private final NotificationOutbox outbox = mock(NotificationOutbox.class);
  private final TransferEmails emails = new TransferEmails(outbox);

  private static TransferRow row(String status) {
    Instant now = Instant.parse("2026-11-12T05:00:00Z");
    return new TransferRow(
        UUID.randomUUID(),
        REFERENCE,
        SENDER,
        RECEIVER,
        UUID.randomUUID(),
        UUID.randomUUID(),
        new BigDecimal("1300.5"),
        "THB",
        "a private note",
        status,
        UUID.randomUUID(),
        LocalDate.parse("2026-11-12"),
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        now,
        now);
  }

  @Test
  void aCompletedTransferEmailsTheSenderAndTheReceiver() {
    emails.statusChanged(row("COMPLETED"));

    verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_SENT, VALUES);
    verify(outbox).enqueue(RECEIVER, EmailKind.TRANSFER_RECEIVED, VALUES);
    verifyNoMoreInteractions(outbox);
  }

  @Test
  void aHeldACancelledAndAnExpiredTransferEmailTheSenderOnly() {
    emails.statusChanged(row("PENDING_REVIEW"));
    emails.statusChanged(row("CANCELLED"));
    emails.statusChanged(row("EXPIRED"));

    verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_HELD, VALUES);
    verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_CANCELLED, VALUES);
    verify(outbox).enqueue(SENDER, EmailKind.TRANSFER_EXPIRED, VALUES);
    verifyNoMoreInteractions(outbox);
  }

  /** A block never emails (Q9); a rejection is emailed by the 5b review, not from here. */
  @ParameterizedTest
  @ValueSource(strings = {"BLOCKED", "REJECTED"})
  void noEmailFromHereForABlockOrARejection(String status) {
    assertThatThrownBy(() -> emails.statusChanged(row(status)))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(outbox);
  }

  @Test
  void theRowIsRequired() {
    assertThatThrownBy(() -> emails.statusChanged(null)).isInstanceOf(NullPointerException.class);
    verify(outbox, org.mockito.Mockito.never()).enqueue(any(), any(), any());
  }
}
