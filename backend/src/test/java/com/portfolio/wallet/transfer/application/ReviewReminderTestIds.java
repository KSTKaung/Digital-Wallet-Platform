package com.portfolio.wallet.transfer.application;

import java.util.UUID;

/** Test only: the reminder's event id of (transfer, officer), for tests in other packages. */
public final class ReviewReminderTestIds {

  private ReviewReminderTestIds() {}

  public static UUID of(UUID transferId, UUID officerId) {
    return ReviewReminderEventIds.of(transferId, officerId);
  }
}
