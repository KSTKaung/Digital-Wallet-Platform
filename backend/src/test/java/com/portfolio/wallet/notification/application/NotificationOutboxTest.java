package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.portfolio.wallet.notification.persistence.NotificationRepository;
import com.portfolio.wallet.support.MutableClock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** What may enter the email outbox (documentations/06 MVP-ADR-25). */
class NotificationOutboxTest {

  private final NotificationRepository notifications = mock(NotificationRepository.class);
  private final MutableClock clock = new MutableClock();
  private final NotificationOutbox outbox = new NotificationOutbox(notifications, clock);
  private final UUID user = UUID.randomUUID();

  @ParameterizedTest
  @EnumSource(
      value = EmailKind.class,
      names = {"KYC_APPROVED", "KYC_REJECTED", "WALLET_FROZEN", "WALLET_UNFROZEN"})
  void aBusinessEmailIsStoredWithTheUserIdAndANewEventId(EmailKind kind) {
    UUID first = outbox.enqueue(user, kind);
    UUID second = outbox.enqueue(user, kind);

    assertThat(first).isNotEqualTo(second);
    // Due at once by the application clock (N2).
    verify(notifications).insert(first, user, kind.name(), Map.of(), clock.instant());
    verify(notifications).insert(second, user, kind.name(), Map.of(), clock.instant());
  }

  /** OTP, registration and account-lock emails stay direct; an OTP must never be stored. */
  @ParameterizedTest
  @EnumSource(
      value = EmailKind.class,
      names = {"OTP", "ACCOUNT_LOCKED", "EXISTING_ACCOUNT_NOTICE"})
  void directEmailsAreRefused(EmailKind kind) {
    assertThatThrownBy(() -> outbox.enqueue(user, kind))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(notifications);
  }

  /** A KYC email declares no value, so nothing unplanned (an address, KYC content) is stored. */
  @Test
  void undeclaredOrNullValuesAreRefusedWithoutEchoingThem() {
    assertThatThrownBy(
            () -> outbox.enqueue(user, EmailKind.KYC_APPROVED, Map.of("email", "a@example.test")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("a@example.test");
    Map<String, String> withNull = new HashMap<>();
    withNull.put("x", null);
    assertThatThrownBy(() -> outbox.enqueue(user, EmailKind.KYC_APPROVED, withNull))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(notifications);
  }

  /** Q9: a money email takes exactly the amount, the currency and the reference. */
  @ParameterizedTest
  @EnumSource(
      value = EmailKind.class,
      names = {
        "DEPOSIT_COMPLETED",
        "WITHDRAWAL_COMPLETED",
        "TRANSFER_SENT",
        "TRANSFER_RECEIVED",
        "TRANSFER_HELD",
        "TRANSFER_REJECTED",
        "TRANSFER_CANCELLED",
        "TRANSFER_EXPIRED"
      })
  void aMoneyEmailIsStoredWithExactlyItsValues(EmailKind kind) {
    Map<String, String> values =
        MoneyEmailValues.of(new java.math.BigDecimal("12.5"), "THB", "TRF-ABC123");

    UUID event = outbox.enqueue(user, kind, values);

    verify(notifications).insert(event, user, kind.name(), values, clock.instant());
    // Missing values, or values from elsewhere, are refused.
    assertThatThrownBy(() -> outbox.enqueue(user, kind))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, String> more = new HashMap<>(values);
    more.put("walletNumber", "12345678901");
    assertThatThrownBy(() -> outbox.enqueue(user, kind, more))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("12345678901");
  }

  @Test
  void theRecipientAndTheKindAreRequired() {
    assertThatThrownBy(() -> outbox.enqueue(null, EmailKind.KYC_APPROVED))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> outbox.enqueue(user, null)).isInstanceOf(NullPointerException.class);
    verifyNoInteractions(notifications);
  }
}
