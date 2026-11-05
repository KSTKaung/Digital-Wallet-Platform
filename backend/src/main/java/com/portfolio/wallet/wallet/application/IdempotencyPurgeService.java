package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deletes expired idempotency records (documentations/07 D04: a scheduled job deletes only expired
 * keys). Each batch is one short {@code DELETE} in its own transaction; rows that a money
 * transaction holds are skipped ({@code SKIP LOCKED}), so the purge neither waits on nor blocks a
 * live movement. The cut-off is the application {@link Clock}, the same time source as the expiry
 * check in {@link IdempotencyService}. A failure is logged by exception class only, and the next
 * run tries again.
 */
@Service
public class IdempotencyPurgeService {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeService.class);
  private static final String TABLE = "wallet.idempotency_records";

  private final IdempotencyRecordRepository records;
  private final IdempotencyProperties properties;
  private final Clock clock;

  public IdempotencyPurgeService(
      IdempotencyRecordRepository records, IdempotencyProperties properties, Clock clock) {
    this.records = records;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Deletes records expired at the start of the run, batch by batch, until a batch comes back
   * short.
   *
   * @return how many records were deleted
   */
  public int purgeExpired() {
    Instant now = clock.instant();
    int batchSize = properties.purge().batchSize();
    int total = 0;
    try {
      int deleted;
      do {
        deleted = records.deleteExpired(now, batchSize);
        total += deleted;
      } while (deleted == batchSize);
    } catch (RuntimeException e) {
      log.warn("Purge failed: table={}, failure={}", TABLE, e.getClass().getSimpleName());
    }
    if (total > 0) {
      log.info("Purged expired rows: table={}, count={}", TABLE, total);
    }
    return total;
  }
}
