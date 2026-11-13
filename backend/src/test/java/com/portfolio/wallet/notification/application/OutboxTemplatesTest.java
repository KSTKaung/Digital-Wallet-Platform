package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The outbox emails' texts (documentations/06 MVP-ADR-25; owner, 2026-11-03, K2). */
class OutboxTemplatesTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final Instant OCCURRED = Instant.parse("2026-11-03T17:30:00Z");

  /** 17:30 UTC on 3 November is 00:30 on 4 November in Bangkok: the business date is shown. */
  @ParameterizedTest
  @EnumSource(
      value = EmailKind.class,
      names = {"KYC_APPROVED", "KYC_REJECTED", "WALLET_FROZEN", "WALLET_UNFROZEN"})
  void everyEmailStatesTheTimeOfTheChangeInTheBusinessZone(EmailKind kind) {
    EmailMessage message = render(kind);

    assertThat(message.kind()).isEqualTo(kind);
    assertThat(message.to()).isEqualTo("c@example.test");
    assertThat(message.body()).contains("4 November 2026 at 00:30:00 (Asia/Bangkok time)");
    assertThat(OutboxTemplates.keys(kind)).contains(Set.of());
  }

  @Test
  void theTextsSayWhatHappenedAndNothingMore() {
    assertThat(render(EmailKind.KYC_APPROVED).body()).contains("approved", "wallet is ready");
    assertThat(render(EmailKind.KYC_REJECTED).body())
        .contains("not approved", "Sign in to see the reason");
    assertThat(render(EmailKind.WALLET_FROZEN).body()).contains("frozen", "contact support");
    assertThat(render(EmailKind.WALLET_UNFROZEN).body()).contains("unfrozen", "contact support");
  }

  @ParameterizedTest
  @EnumSource(
      value = EmailKind.class,
      names = {"OTP", "ACCOUNT_LOCKED", "EXISTING_ACCOUNT_NOTICE"})
  void directEmailsHaveNoOutboxTemplate(EmailKind kind) {
    assertThat(OutboxTemplates.keys(kind)).isEmpty();
    assertThatThrownBy(() -> render(kind)).isInstanceOf(IllegalArgumentException.class);
  }

  // Money emails (Q9) --------------------------------------------------------------------------

  private static final String REFERENCE = "TRF-0123456789ABCDEF0123456789ABCDEF";
  private static final Map<String, String> VALUES =
      Map.of("amount", "1234567.50", "currency", "THB", "reference", REFERENCE);

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
  void aMoneyEmailStatesTheAmountTheReferenceAndTheTimeAndNothingAboutRisk(EmailKind kind) {
    EmailMessage message =
        OutboxTemplates.render(kind, "c@example.test", OCCURRED, BANGKOK, VALUES);

    assertThat(OutboxTemplates.keys(kind)).contains(Set.of("amount", "currency", "reference"));
    assertThat(message.body())
        .contains("1,234,567.50 THB", "Reference: " + REFERENCE, "4 November 2026 at 00:30:00");
    String text = message.subject() + " " + message.body();
    assertThat(text.toLowerCase(java.util.Locale.ROOT))
        .doesNotContain("risk", "score", "rule", "balance", "suspicious", "fraud");
  }

  @Test
  void theMoneyTextsSayWhatHappened() {
    assertThat(money(EmailKind.DEPOSIT_COMPLETED).subject())
        .isEqualTo("Your deposit was completed");
    assertThat(money(EmailKind.WITHDRAWAL_COMPLETED).subject())
        .isEqualTo("Your withdrawal was completed");
    assertThat(money(EmailKind.TRANSFER_SENT).subject()).isEqualTo("Your transfer was completed");
    assertThat(money(EmailKind.TRANSFER_RECEIVED).subject()).isEqualTo("You received a transfer");
    assertThat(money(EmailKind.TRANSFER_HELD).body()).contains("waiting for review", "cancel");
    assertThat(money(EmailKind.TRANSFER_REJECTED).body())
        .contains("not completed", "Sign in to see the reason");
    assertThat(money(EmailKind.TRANSFER_CANCELLED).body()).contains("cancelled");
    assertThat(money(EmailKind.TRANSFER_EXPIRED).body()).contains("expired");
  }

  /** A row whose values do not match its kind fails its attempt; nothing half-rendered is sent. */
  @Test
  void valuesThatDoNotMatchTheKindAreRefused() {
    assertThatThrownBy(
            () ->
                OutboxTemplates.render(
                    EmailKind.TRANSFER_SENT, "c@example.test", OCCURRED, BANGKOK, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OutboxTemplates.render(
                    EmailKind.KYC_APPROVED, "c@example.test", OCCURRED, BANGKOK, VALUES))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, String> notANumber = new java.util.HashMap<>(VALUES);
    notANumber.put("amount", "lots");
    assertThatThrownBy(
            () ->
                OutboxTemplates.render(
                    EmailKind.TRANSFER_SENT, "c@example.test", OCCURRED, BANGKOK, notANumber))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static EmailMessage money(EmailKind kind) {
    return OutboxTemplates.render(kind, "c@example.test", OCCURRED, BANGKOK, VALUES);
  }

  private static EmailMessage render(EmailKind kind) {
    return OutboxTemplates.render(kind, "c@example.test", OCCURRED, BANGKOK);
  }
}
