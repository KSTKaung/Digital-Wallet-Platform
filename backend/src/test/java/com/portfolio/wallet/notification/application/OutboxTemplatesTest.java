package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
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

  private static EmailMessage render(EmailKind kind) {
    return OutboxTemplates.render(kind, "c@example.test", OCCURRED, BANGKOK);
  }
}
