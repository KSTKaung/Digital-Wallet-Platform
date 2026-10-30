package com.portfolio.wallet.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Assertions on PostgreSQL errors by SQLSTATE and, where meaningful, constraint name. */
public final class SqlAssertions {

  public static final String CHECK_VIOLATION = "23514";
  public static final String UNIQUE_VIOLATION = "23505";
  public static final String FOREIGN_KEY_VIOLATION = "23503";
  public static final String NOT_NULL_VIOLATION = "23502";
  public static final String LOCK_NOT_AVAILABLE = "55P03";
  public static final String RESTRICT_VIOLATION = "23001";
  public static final String INSUFFICIENT_PRIVILEGE = "42501";

  private SqlAssertions() {}

  /**
   * Runs {@code action} and asserts that it fails with a {@link SQLException} carrying {@code
   * sqlState} (anywhere in the cause chain, so Spring-translated exceptions work too).
   */
  public static SQLException assertSqlState(String sqlState, ThrowingCallable action) {
    Throwable thrown = catchThrowable(action);
    assertThat(thrown).as("expected a database error with SQLSTATE %s", sqlState).isNotNull();
    SQLException sql = findSqlException(thrown);
    // Cast: SQLException is both a Throwable and an Iterable, so assertThat(sql) is ambiguous.
    assertThat((Throwable) sql).as("no SQLException in the cause chain of %s", thrown).isNotNull();
    assertThat(sql.getSQLState()).as("SQLSTATE of: %s", sql.getMessage()).isEqualTo(sqlState);
    return sql;
  }

  /** Asserts a check or unique violation of the named constraint. */
  public static void assertViolates(String sqlState, String constraint, ThrowingCallable action) {
    SQLException sql = assertSqlState(sqlState, action);
    assertThat(sql.getMessage()).contains("\"" + constraint + "\"");
  }

  public static void assertCheckViolation(String constraint, ThrowingCallable action) {
    assertViolates(CHECK_VIOLATION, constraint, action);
  }

  public static void assertUniqueViolation(String constraint, ThrowingCallable action) {
    assertViolates(UNIQUE_VIOLATION, constraint, action);
  }

  public static void assertForeignKeyViolation(String constraint, ThrowingCallable action) {
    assertViolates(FOREIGN_KEY_VIOLATION, constraint, action);
  }

  /** Asserts a trigger rejection (restrict_violation) whose message contains {@code fragment}. */
  public static void assertRestrictViolation(String fragment, ThrowingCallable action) {
    SQLException sql = assertSqlState(RESTRICT_VIOLATION, action);
    assertThat(sql.getMessage()).contains(fragment);
  }

  public static void assertPermissionDenied(ThrowingCallable action) {
    assertSqlState(INSUFFICIENT_PRIVILEGE, action);
  }

  private static SQLException findSqlException(Throwable thrown) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql) {
        return sql;
      }
    }
    return null;
  }
}
