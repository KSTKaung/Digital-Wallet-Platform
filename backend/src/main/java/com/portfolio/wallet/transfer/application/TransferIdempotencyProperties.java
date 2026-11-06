package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.idempotency.IdempotencySettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Idempotency records of transfers ({@code transfer.idempotency_records}). Checked at startup, with
 * the checks shared by every module ({@link IdempotencySettings}).
 *
 * @param ttl how long a key is retained: 24 hours (documentations/02 §1.4; doc 07 D04). At most 30
 *     days (doc 03 §8) and at least 1 second, so {@code expires_at} always lies after {@code
 *     created_at} (V11 {@code ck_transfer_idempotency_records_expiry})
 * @param purge the scheduled job that deletes expired records
 */
@ConfigurationProperties(prefix = "app.transfer.idempotency")
public record TransferIdempotencyProperties(Duration ttl, Purge purge) {

  private static final String PREFIX = "app.transfer.idempotency.";

  public TransferIdempotencyProperties {
    IdempotencySettings.requireValidTtl(PREFIX, ttl);
    if (purge == null) {
      throw new IllegalArgumentException(PREFIX + "purge must be configured");
    }
  }

  /**
   * @param enabled turns the scheduled job on; tests turn it off and call the purge directly
   * @param interval how often the job runs; not a documented value
   * @param batchSize the most rows one DELETE statement removes, so each statement is short; not a
   *     documented value
   */
  public record Purge(boolean enabled, Duration interval, int batchSize) {

    public Purge {
      IdempotencySettings.requireValidPurge(PREFIX, interval, batchSize);
    }
  }
}
