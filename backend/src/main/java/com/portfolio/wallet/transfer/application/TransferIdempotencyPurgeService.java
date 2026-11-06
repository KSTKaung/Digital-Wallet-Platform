package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.idempotency.IdempotencyPurge;
import com.portfolio.wallet.transfer.persistence.TransferIdempotencyRecordRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Deletes expired transfer idempotency records (documentations/07 D04: a scheduled job deletes only
 * expired keys; doc 06 MVP-ADR-06 "Clean-up jobs") with the shared {@link IdempotencyPurge} over
 * the transfer module's own store: batches of {@code app.transfer.idempotency.purge.batch-size},
 * each one short {@code DELETE} in its own transaction that skips rows a money transaction holds
 * ({@code SKIP LOCKED}). The cut-off is the application {@link Clock}, the same time source as the
 * expiry check in {@link TransferIdempotency}. A failure is logged by exception class only, and the
 * next run tries again.
 */
@Service
public class TransferIdempotencyPurgeService {

  static final String TABLE = "transfer.idempotency_records";

  private final IdempotencyPurge purge;

  public TransferIdempotencyPurgeService(
      TransferIdempotencyRecordRepository records,
      TransferIdempotencyProperties properties,
      Clock clock) {
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
