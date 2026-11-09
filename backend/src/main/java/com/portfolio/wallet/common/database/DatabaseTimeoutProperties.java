package com.portfolio.wallet.common.database;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lock and statement timeouts of the money, review and expiry transactions ({@code app.database};
 * documentations/06 MVP-ADR-06 "Lock and statement timeouts"; doc 07 D14; doc 08 §4C.2 Q6). The MVP
 * defaults, 5 s and 10 s, live in {@code application.yaml}; there is no default here.
 *
 * <p>Checked when the application starts, so a bad value stops the start instead of reaching a
 * money transaction:
 *
 * <ul>
 *   <li>both are present and positive (PostgreSQL reads {@code 0} as "no timeout", which would
 *       bring back the unbounded waits of doc 07 D14 open risk Z1);
 *   <li>both are whole milliseconds, the unit PostgreSQL keeps, so nothing is rounded silently;
 *   <li>both fit PostgreSQL's limit for these settings, {@value #MAX_MILLIS} ms;
 *   <li>{@code statementTimeout >= lockTimeout}, so a statement waiting on one lock is normally
 *       ended by the lock timeout. Each lock wait is bounded on its own, so a statement that waits
 *       on several locks (or runs long between them) can still reach the statement timeout first;
 *       either way the result is the same: {@code 500 SYSTEM_ERROR}, the transaction rolled back,
 *       and nothing stored for the {@code Idempotency-Key}.
 * </ul>
 *
 * Messages name the property, never a value.
 *
 * @param lockTimeout the longest one statement waits for a lock ({@code lock_timeout})
 * @param statementTimeout the longest one statement runs ({@code statement_timeout})
 */
@ConfigurationProperties(prefix = "app.database")
public record DatabaseTimeoutProperties(Duration lockTimeout, Duration statementTimeout) {

  /** PostgreSQL's maximum for {@code lock_timeout} and {@code statement_timeout}, in ms. */
  public static final long MAX_MILLIS = Integer.MAX_VALUE;

  private static final String PREFIX = "app.database.";

  public DatabaseTimeoutProperties {
    requireValid(lockTimeout, "lock-timeout");
    requireValid(statementTimeout, "statement-timeout");
    if (statementTimeout.compareTo(lockTimeout) < 0) {
      throw new IllegalArgumentException(
          PREFIX + "statement-timeout must not be shorter than " + PREFIX + "lock-timeout");
    }
  }

  private static void requireValid(Duration value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(PREFIX + name + " is required");
    }
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(PREFIX + name + " must be positive");
    }
    if (value.compareTo(Duration.ofMillis(MAX_MILLIS)) > 0) {
      throw new IllegalArgumentException(PREFIX + name + " must be at most " + MAX_MILLIS + " ms");
    }
    if (value.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException(PREFIX + name + " must be whole milliseconds");
    }
  }
}
