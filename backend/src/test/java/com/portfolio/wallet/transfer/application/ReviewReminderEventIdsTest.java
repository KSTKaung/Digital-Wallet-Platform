package com.portfolio.wallet.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The deterministic event id of a review reminder (MVP-ADR-24 "Jobs (Q11)"). */
class ReviewReminderEventIdsTest {

  private static final UUID TRANSFER = UUID.fromString("0f8e2a56-6a43-4c39-9a6f-3ad0b4c6e001");
  private static final UUID OFFICER = UUID.fromString("7d0c41b2-1e2f-4a7b-8c3d-5e6f7a8b9c02");

  /** The RFC 9562 / Python {@code uuid5(NAMESPACE_DNS, 'python.org')} value. */
  @Test
  void theNameBasedIdIsStandardVersion5() {
    UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    assertThat(ReviewReminderEventIds.nameBased(dns, "python.org"))
        .isEqualTo(UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"));
  }

  @Test
  void theSameTransferAndOfficerAlwaysGiveTheSameId() {
    UUID id = ReviewReminderEventIds.of(TRANSFER, OFFICER);

    assertThat(ReviewReminderEventIds.of(TRANSFER, OFFICER)).isEqualTo(id);
    assertThat(id.version()).isEqualTo(5);
    assertThat(id.variant()).isEqualTo(2);
  }

  /** One id per pair; the two parts are never interchangeable. */
  @Test
  void anotherTransferOrOfficerGivesAnotherId() {
    UUID id = ReviewReminderEventIds.of(TRANSFER, OFFICER);

    assertThat(ReviewReminderEventIds.of(TRANSFER, UUID.randomUUID())).isNotEqualTo(id);
    assertThat(ReviewReminderEventIds.of(UUID.randomUUID(), OFFICER)).isNotEqualTo(id);
    assertThat(ReviewReminderEventIds.of(OFFICER, TRANSFER)).isNotEqualTo(id);
  }

  @Test
  void bothPartsAreRequired() {
    assertThatThrownBy(() -> ReviewReminderEventIds.of(null, OFFICER))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReviewReminderEventIds.of(TRANSFER, null))
        .isInstanceOf(NullPointerException.class);
  }
}
