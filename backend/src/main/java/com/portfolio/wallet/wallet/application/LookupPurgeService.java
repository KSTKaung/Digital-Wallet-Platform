package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.persistence.LookupAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deletes lookup attempts that no window can count any more (documentations/07 §1.1: "old rows
 * purged"; MVP-ADR-20). An attempt only counts while it is inside the sliding window, so anything
 * older than {@link LookupProperties#window()} is deleted; every lookup has its own audit row,
 * which is the lasting record. Batches of one short statement each; a failure is logged by
 * exception class only, and the next run tries again. The cut-off is the database's {@code
 * statement_timestamp()}, the clock that sets {@code attempted_at} and that the windows are
 * measured against (Y1).
 */
@Service
public class LookupPurgeService {

  private static final Logger log = LoggerFactory.getLogger(LookupPurgeService.class);
  private static final String TABLE = "wallet.lookup_attempts";

  private final LookupAttemptRepository attempts;
  private final LookupProperties properties;

  public LookupPurgeService(LookupAttemptRepository attempts, LookupProperties properties) {
    this.attempts = attempts;
    this.properties = properties;
  }

  /**
   * @return how many attempts were deleted
   */
  public int purgeExpired() {
    long retention = properties.window().toSeconds();
    int batchSize = properties.purge().batchSize();
    int total = 0;
    try {
      int deleted;
      do {
        deleted = attempts.deleteOlderThan(retention, batchSize);
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
