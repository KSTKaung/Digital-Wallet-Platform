package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.common.idempotency.IdempotencyPurge;
import com.portfolio.wallet.wallet.persistence.IdempotencyRecordRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Deletes expired deposit and withdrawal idempotency records (documentations/07 D04: a scheduled
 * job deletes only expired keys) with the shared {@link IdempotencyPurge} over the wallet's own
 * store: batches of {@code app.wallet.idempotency.purge.batch-size}, each one short {@code DELETE}
 * in its own transaction that skips rows a money transaction holds ({@code SKIP LOCKED}). The
 * cut-off is the application {@link Clock}, the same time source as the expiry check in {@link
 * IdempotencyService}. A failure is logged by exception class only, and the next run tries again.
 */
@Service
public class IdempotencyPurgeService {

  static final String TABLE = "wallet.idempotency_records";

  private final IdempotencyPurge purge;

  public IdempotencyPurgeService(
      IdempotencyRecordRepository records, IdempotencyProperties properties, Clock clock) {
    this.purge = new IdempotencyPurge(records, TABLE, properties.purge().batchSize(), clock);
  }

  /**
   * Deletes records expired at the start of the run, batch by batch, until a batch comes back
   * short.
   *
   * @return how many records were deleted
   */
  public int purgeExpired() {
    return purge.purgeExpired();
  }
}
