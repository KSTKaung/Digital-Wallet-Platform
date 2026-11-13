package com.portfolio.wallet.common.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes one module's expired idempotency records (documentations/07 D04: a scheduled job deletes
 * only expired keys). Each batch is one short {@code DELETE} in its own transaction; rows that a
 * money transaction holds are skipped ({@code SKIP LOCKED}, in the store's SQL), so the purge
 * neither waits on nor blocks a live movement. The cut-off is the application {@link Clock}, the
 * same time source as the expiry check in {@link IdempotencyEngine}. A failure is logged by
 * exception class only (its message can hold SQL and row values), and the next run tries again.
 *
 * <p>A plain class, not a bean: each module builds one over its own store and names its own table,
 * which appears only in the log lines.
 */
public final class IdempotencyPurge {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyPurge.class);
  private static final Pattern TABLE = Pattern.compile("[a-z][a-z_]*\\.[a-z][a-z_]*");

  private final IdempotencyStore store;
  private final String table;
  private final int batchSize;
  private final Clock clock;

  /**
   * @param table the store's table, schema-qualified, such as {@code wallet.idempotency_records}
   * @param batchSize the most rows one statement deletes; at least 1
   */
  public IdempotencyPurge(IdempotencyStore store, String table, int batchSize, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.table = Objects.requireNonNull(table, "table");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (!TABLE.matcher(table).matches()) {
      throw new IllegalArgumentException("table must be a schema-qualified table name");
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("batch size must be at least 1");
    }
    this.batchSize = batchSize;
  }

  /**
   * Deletes records expired at the start of the run, batch by batch, until a batch comes back
   * short. Each {@link IdempotencyStore#deleteExpired} call is its own transaction, so the caller
   * holds none.
   *
   * @return how many records were deleted
   */
  public int purgeExpired() {
    Instant now = clock.instant();
    int total = 0;
    try {
      int deleted;
      do {
        deleted = store.deleteExpired(now, batchSize);
        total += deleted;
      } while (deleted == batchSize);
    } catch (RuntimeException e) {
      log.warn("Purge failed: table={}, failure={}", table, e.getClass().getSimpleName());
    }
    if (total > 0) {
      log.info("Purged expired rows: table={}, count={}", table, total);
    }
    return total;
  }
}
